package dev.spatialfin.fcast

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.spatialfin.presentation.theme.SpatialFinTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.jdtech.jellyfin.player.core.splitav.ReceiverAudioCodecs
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.ui.PlayerView
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.AudioRouteInfo
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamIntentCodec
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamSource
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundSession
import dev.jdtech.jellyfin.player.core.external.ExternalStreamMediaPreparer
import dev.jdtech.jellyfin.player.xr.LibassRenderer
import dev.jdtech.jellyfin.player.xr.LibassTextRenderer
import dev.spatialfin.R
import dev.spatialfin.fcast.session.ReceiverAudioClock
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Flat ExoPlayer receiver Activity for an arbitrary progressive or adaptive FCast source. We
 * deliberately avoid the library-backed Beam / TV player Activities here — those
 * assume a Jellyfin item ID and pull subtitles, recommendations, voice services etc. through
 * `SpatialPlayerScreen`. For an external URL all that machinery is meaningless and the surgery
 * to teach those screens about itemId-less playback is its own change.
 *
 * On Galaxy XR this remains the 2D path when Full Space inbound playback is disabled or the
 * request is split-A/V audio; ordinary video is routed to `XrFCastInboundPlayerActivity`.
 *
 * Lifecycle bridge: registers an [ExternalStreamPlayer] with [FCastInboundSession] so that
 * inbound FCast Pause/Resume/Seek/SetVolume/SetSpeed frames re-enter the running ExoPlayer,
 * and pushes [PlaybackUpdateMessage] back through the same bridge so the sender's
 * mini-controller reflects real state.
 */
@AndroidEntryPoint
class FCastInboundPlayerActivity : ComponentActivity() {

    /**
     * Hilt-injected so we can fetch embedded MKV font attachments when the cast URL points at
     * a Jellyfin item. Activity stays lightweight when the URL isn't Jellyfin (third-party
     * sender, raw HTTP stream, etc.) — the deferred just completes with an empty list.
     */
    @Inject lateinit var repository: JellyfinRepository

    private var player: ExoPlayer? = null
    private var playbackTickerJob: Job? = null
    private var subtitleRenderJob: Job? = null
    private var libassRenderer: LibassRenderer? = null
    private var libassFontsDeferred: CompletableDeferred<List<Pair<String, ByteArray>>>? = null
    private var splitAvRole: SplitAvRole? = null
    private var playbackTickerIntervalMs: Long = NORMAL_TICKER_INTERVAL_MS
    private val mediaPreparer by lazy {
        ExternalStreamMediaPreparer(this, parseSubtitlesDuringExtraction = false)
    }
    
    private val libassBitmapState = MutableStateFlow<Bitmap?>(null)
    private val titleState = MutableStateFlow<String?>("FCast Media")
    private val thumbnailUrlState = MutableStateFlow<String?>(null)
    private val audioInfoLineState = MutableStateFlow<String?>(null)
    private val isAudioOnlyState = MutableStateFlow(false)
    private val remoteInteractionTick = MutableStateFlow(0L)
    
    /** Passthrough-capable audio codec tokens of the attached chain; set in [logAudioCapabilities]. */
    private var receiverAudioCodecs: List<String> = emptyList()
    private var receiverAudioRouteInfo: AudioRouteInfo? = null
    private var preferredAudioLanguage: String? = null
    private var preferredAudioLanguageApplied = false
    private var audioSinkPositionOffsetUs: Long? = null
    @OptIn(UnstableApi::class)
    private var audioSinkTimingProbe: TimingAudioSink? = null
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushPlaybackSnapshot()
        override fun onPlaybackStateChanged(playbackState: Int) = pushPlaybackSnapshot()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // Drop the libass per-event cache on every seek — otherwise the post-seek frame
            // is composited on top of stale glyph buffers from before the seek, producing a
            // "ghost subtitle" for one frame. Mirrors the same fix the main player uses
            // (LibassTextRenderer.onPositionReset → libassRenderer.clearCache).
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                libassRenderer?.clearCache()
                audioSinkPositionOffsetUs = null
            }
            pushPlaybackSnapshot()
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            pushPlaybackSnapshot()
        override fun onVolumeChanged(volume: Float) {
            FCastInboundSession.pushVolumeUpdate(
                VolumeUpdateMessage(
                    generationTime = System.currentTimeMillis(),
                    volume = volume.toDouble(),
                )
            )
        }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            applyPreferredAudioLanguageIfNeeded(tracks)
            pushTracksSnapshot()
        }
    }

    private val scheduleHandler = Handler(Looper.getMainLooper())
    private var scheduledResume: Runnable? = null

    /** Cancel any pending v4 synchronized-start so a later pause/resume/stop wins. */
    private fun cancelScheduledResume() {
        scheduledResume?.let { scheduleHandler.removeCallbacks(it) }
        scheduledResume = null
    }

    private val control = object : ExternalStreamPlayer {
        // Inbound senders never call this — Play frames go through the Intent-based path —
        // but the interface requires it.
        override fun play(request: ExternalStreamRequest): ExternalStreamPlayer.PlayResult =
            ExternalStreamPlayer.PlayResult.Rejected("Use the Intent-based play path")

        override fun pause() = runOnUiThread {
            cancelScheduledResume()
            Timber.tag(TAG).i("control.pause role=%s", splitAvRole?.name ?: "fullAv")
            player?.pause()
            pushPlaybackSnapshot()
        }.let { Unit }
        override fun resume() = runOnUiThread {
            cancelScheduledResume()
            Timber.tag(TAG).i("control.resume role=%s", splitAvRole?.name ?: "fullAv")
            player?.play()
            pushPlaybackSnapshot()
        }.let { Unit }

        /**
         * v4 synchronized start: begin playback exactly when this device's monotonic clock
         * reaches [atReceiverMonotonicMs] (the sender mapped its target instant into our clock
         * via the NTP offset θ). Clamp hard — a past or absurdly-far instant resumes now —
         * so a bad estimate can never be worse than the legacy resume-now path.
         */
        override fun resumeAt(atReceiverMonotonicMs: Long) = runOnUiThread {
            cancelScheduledResume()
            val delay = atReceiverMonotonicMs - SystemClock.elapsedRealtime()
            Timber.tag(TAG).i(
                "control.resumeAt target=%d now=%d delay=%d role=%s",
                atReceiverMonotonicMs,
                SystemClock.elapsedRealtime(),
                delay,
                splitAvRole?.name ?: "fullAv",
            )
            if (delay <= 0L || delay > MAX_SCHEDULED_START_WAIT_MS) {
                player?.play()
                pushPlaybackSnapshot()
            } else {
                val r = Runnable {
                    Timber.tag(TAG).i("control.resumeAt firing scheduled play")
                    player?.play()
                    pushPlaybackSnapshot()
                    scheduledResume = null
                }
                scheduledResume = r
                scheduleHandler.postDelayed(r, delay)
            }
        }.let { Unit }
        override fun stop() = runOnUiThread {
            cancelScheduledResume()
            player?.stop()
            finish()
        }.let { Unit }
        override fun seek(seconds: Double) = runOnUiThread {
            // Clear libass first so the post-seek frame renders against an empty event cache.
            // The Player.Listener's onPositionDiscontinuity also clears it, but doing this
            // here on the inbound-FCast path keeps the bitmap clean even if the player coalesces
            // the seek before the listener fires.
            libassRenderer?.clearCache()
            audioSinkPositionOffsetUs = null
            player?.seekTo((seconds * 1000.0).toLong().coerceAtLeast(0L))
        }.let { Unit }
        override fun setVolume(volume: Double) = runOnUiThread {
            player?.volume = volume.toFloat().coerceIn(0f, 1f)
        }.let { Unit }
        override fun setSpeed(speed: Double) = runOnUiThread {
            val s = speed.toFloat().coerceIn(0.25f, 4f)
            player?.setPlaybackSpeed(s)
        }.let { Unit }
        override fun setTrack(type: Int, trackId: String) = runOnUiThread {
            val p = player ?: return@runOnUiThread
            val index = trackId.toIntOrNull() ?: return@runOnUiThread
            if (index < 0 || index >= p.currentTracks.groups.size) return@runOnUiThread
            val group = p.currentTracks.groups[index]
            if (group.type != type) return@runOnUiThread
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
                .setTrackTypeDisabled(type, false)
                .build()
        }.let { Unit }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen from turning off while playing video
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Inbound player is always full-bleed video — hide system bars on every form factor
        // so the projected/cast image isn't framed by clocks, network chips, or nav buttons.
        applyImmersiveSystemBars()

        val exo = buildPlayerWithLibass()
        player = exo
        exo.addListener(playerListener)
        startSubtitleRenderLoop()

        setContent {
            SpatialFinTheme {
                val libassBitmap by libassBitmapState.collectAsState()
                val title by titleState.collectAsState()
                val thumbnailUrl by thumbnailUrlState.collectAsState()
                val audioInfoLine by audioInfoLineState.collectAsState()
                val isAudioOnly by isAudioOnlyState.collectAsState()
                val remoteTick by remoteInteractionTick.collectAsState()

                FCastReceiverScreen(
                    player = exo,
                    title = title,
                    thumbnailUrl = thumbnailUrl,
                    audioInfoLine = audioInfoLine,
                    libassBitmap = libassBitmap,
                    isAudioOnly = isAudioOnly,
                    remoteInteractionTick = remoteTick,
                    onStop = {
                        player?.stop()
                        finish()
                    }
                )
            }
        }

        if (!applyIntent(intent)) finish()
    }

    /** See `UnifiedMainActivity.applyImmersiveSystemBars` — same sticky-immersive recipe. */
    private fun applyImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveSystemBars()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        return handleRemoteKey(event)
    }

    private fun handleRemoteKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekByRemote(-REMOTE_SEEK_BACK_MS)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekByRemote(REMOTE_SEEK_FORWARD_MS)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (event.repeatCount == 0) togglePlaybackByRemote()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                markRemoteInteraction()
                pushPlaybackSnapshot()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                markRemoteInteraction()
                pushPlaybackSnapshot()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN -> {
                markRemoteInteraction()
                false
            }
            else -> false
        }
    }

    private fun togglePlaybackByRemote() {
        val p = player ?: return
        if (p.playWhenReady || p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
        markRemoteInteraction()
        pushPlaybackSnapshot()
    }

    private fun seekByRemote(deltaMs: Long) {
        val p = player ?: return
        val unclamped = p.currentPosition.coerceAtLeast(0L) + deltaMs
        val durationMs = p.duration.takeIf { it > 0L && it != C.TIME_UNSET }
        val targetMs = durationMs?.let { unclamped.coerceIn(0L, it) } ?: unclamped.coerceAtLeast(0L)
        libassRenderer?.clearCache()
        audioSinkPositionOffsetUs = null
        p.seekTo(targetMs)
        markRemoteInteraction()
        pushPlaybackSnapshot()
    }

    private fun markRemoteInteraction() {
        remoteInteractionTick.value = SystemClock.elapsedRealtime()
    }

    /**
     * Fire-and-forget Jellyfin font fetch for the cast URL. Completes the [libassFontsDeferred]
     * with the resolved (name, bytes) pairs, or an empty list when the URL isn't a Jellyfin
     * stream / the receiver can't resolve the item. Called from [applyIntent] every time a new
     * Play arrives so each cast in a single session gets its own font set.
     */
    private fun preloadLibassFontsAsync(url: String?) {
        val deferred = CompletableDeferred<List<Pair<String, ByteArray>>>().also {
            libassFontsDeferred = it
        }
        if (url == null) {
            deferred.complete(emptyList())
            return
        }
        val itemId = extractJellyfinItemId(url)
        if (itemId == null) {
            deferred.complete(emptyList())
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fonts = runCatching {
                val sources = repository.getMediaSources(itemId = itemId, includePath = false)
                val candidates = sources.flatMap { source ->
                    source.mediaAttachments.map { source to it }
                }.filter { (_, att) -> isFontAttachment(att.fileName, att.mimeType, att.codec) }
                Timber.tag(TAG).i(
                    "preloadLibassFontsAsync: candidate fonts=%d for itemId=%s",
                    candidates.size, itemId,
                )
                candidates.mapNotNull { (source, attachment) ->
                    val fileName = attachment.fileName.ifBlank { "attachment-${source.id}-${attachment.index}" }
                    repository.getMediaAttachment(itemId, source.id, attachment.index)
                        ?.let { fileName to it }
                }
            }.onFailure {
                Timber.tag(TAG).w(it, "preloadLibassFontsAsync failed for %s", itemId)
            }.getOrDefault(emptyList())
            deferred.complete(fonts)
        }
    }

    /**
     * Extract the Jellyfin item UUID from a `/Videos/<uuid>/stream` style URL. Returns null
     * for non-Jellyfin or malformed URLs — we then skip the font fetch entirely.
     */
    private fun extractJellyfinItemId(url: String): UUID? {
        val match = JELLYFIN_VIDEO_PATH_REGEX.find(url) ?: return null
        return runCatching { UUID.fromString(match.groupValues[1]) }.getOrNull()
    }

    private fun isFontAttachment(fileName: String, mimeType: String, codec: String): Boolean {
        val nameL = fileName.lowercase()
        val mimeL = mimeType.lowercase()
        val codecL = codec.lowercase()
        return mimeL.contains("font") ||
            mimeL.contains("truetype") ||
            mimeL.contains("opentype") ||
            nameL.endsWith(".ttf") ||
            nameL.endsWith(".otf") ||
            nameL.endsWith(".ttc") ||
            nameL.endsWith(".otc") ||
            codecL.contains("ttf") ||
            codecL.contains("otf")
    }

    /**
     * Build an ExoPlayer that routes ASS/SSA/SRT/VTT text tracks through [LibassTextRenderer]
     * → [LibassRenderer]. Mirrors the wiring [dev.jdtech.jellyfin.player.beam.BeamPlayerActivity]
     * uses for its 2D player — the FCast inbound activity is the SpatialFin → SpatialFin cast
     * receiver's player, so when a SpatialFin sender pushes a Jellyfin item with embedded ASS
     * subs the receiver renders them with full libass fidelity instead of plain dialogue.
     *
     * Defensive fallback: if the native `libass_jni.so` couldn't be loaded for any reason, the
     * builder degrades silently to ExoPlayer's default text renderer — ASS will look plain but
     * the cast still plays.
     */
    /**
     * Log what the system audio sink reports as supported encodings — including HDMI / SPDIF
     * passthrough formats advertised by an attached AVR/soundbar. Lets us diagnose "no audio
     * on multichannel" by seeing whether passthrough is enabled at the system level.
     *
     * Output looks like:
     * ```
     * AudioCapabilities: maxChannelCount=8 supports=[PCM_16BIT, AC3, E_AC3, E_AC3_JOC, TRUEHD, DTS, DTS_HD]
     * ```
     * If `AC3` / `E_AC3` are absent on a setup that has a Dolby-capable soundbar, the system
     * audio setting is forcing PCM (Google TV → Display & Sound → Advanced sound settings →
     * Surround sound: change from "PCM" to "Auto" or "Pass-through").
     */
    @OptIn(UnstableApi::class)
    private fun logAudioCapabilities() {
        val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(this)
        // Stash the passthrough-capable codec tokens so every beacon advertises them to the
        // sender. This is the authoritative input to the sender's split-A/V direct-stream vs
        // transcode decision — see [ReceiverAudioCodecs] / SplitAvController.
        receiverAudioCodecs = ReceiverAudioCodecs.fromCapabilities(caps)
        receiverAudioRouteInfo = buildAudioRouteInfo(caps, receiverAudioCodecs)
        val encodings = listOf(
            android.media.AudioFormat.ENCODING_PCM_16BIT to "PCM_16BIT",
            android.media.AudioFormat.ENCODING_PCM_FLOAT to "PCM_FLOAT",
            android.media.AudioFormat.ENCODING_AC3 to "AC3",
            android.media.AudioFormat.ENCODING_E_AC3 to "E_AC3",
            android.media.AudioFormat.ENCODING_E_AC3_JOC to "E_AC3_JOC_ATMOS",
            android.media.AudioFormat.ENCODING_DOLBY_TRUEHD to "TRUEHD",
            android.media.AudioFormat.ENCODING_DTS to "DTS",
            android.media.AudioFormat.ENCODING_DTS_HD to "DTS_HD",
            android.media.AudioFormat.ENCODING_DTS_UHD_P2 to "DTS_UHD",
            android.media.AudioFormat.ENCODING_OPUS to "OPUS",
        )
        val supported = encodings
            .filter { (encoding, _) -> caps.supportsEncoding(encoding) }
            .joinToString { it.second }
        Timber.tag(TAG).i(
            "AudioCapabilities: maxChannelCount=%d supports=[%s]",
            caps.maxChannelCount, supported,
        )
    }

    @OptIn(UnstableApi::class)
    private fun buildAudioRouteInfo(
        caps: androidx.media3.exoplayer.audio.AudioCapabilities,
        codecs: List<String>,
    ): AudioRouteInfo {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = getSystemService(AudioManager::class.java)
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.sortedWith(compareByDescending<AudioDeviceInfo> { it.isLikelyExternalAudioRoute() }
                    .thenBy { it.type })
                ?.firstOrNull()
        } else {
            null
        }
        val deviceType = device?.let { audioDeviceTypeName(it.type) }
        val deviceLabel = device?.productName?.toString()?.takeIf { it.isNotBlank() }
        val label = when {
            !deviceLabel.isNullOrBlank() -> deviceLabel
            deviceType != null -> deviceType
            else -> "Android audio output"
        }
        val normalizedCodecs = codecs.map { it.lowercase().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val fingerprintSource = listOf(
            deviceType.orEmpty(),
            label,
            caps.maxChannelCount.toString(),
            normalizedCodecs.sorted().joinToString("|"),
        ).joinToString("#")
        return AudioRouteInfo(
            fingerprint = sha256Short(fingerprintSource),
            label = label,
            deviceType = deviceType,
            supportedAudioCodecs = normalizedCodecs.takeIf { it.isNotEmpty() },
            maxChannelCount = caps.maxChannelCount,
        )
    }

    private fun sha256Short(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun audioDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "built-in earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_DOCK -> "dock"
        AudioDeviceInfo.TYPE_FM -> "FM"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in microphone"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV tuner"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_AUX_LINE -> "aux line"
        AudioDeviceInfo.TYPE_IP -> "IP audio"
        AudioDeviceInfo.TYPE_BUS -> "bus audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "built-in speaker"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
        else -> "audio device $type"
    }

    private fun AudioDeviceInfo.isLikelyExternalAudioRoute(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_AUX_LINE,
        -> true
        else -> false
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayerWithLibass(): ExoPlayer {
        // Print what passthrough/decode the system advertises — invaluable for diagnosing
        // "no audio on this title but works for that one" reports. Logged once per Activity
        // launch since capabilities don't change without a device reconfig.
        logAudioCapabilities()
        // 1920×1080 render canvas; resized to the on-screen panel below once we know the
        // actual layout size. The XR libass renderer caches one bitmap of this size; a too-low
        // initial value would force a re-alloc on first frame.
        val renderer = runCatching {
            if (LibassRenderer.isAvailable()) {
                LibassRenderer(1920, 1080).apply { init() }
            } else {
                Timber.tag(TAG).w("libass unavailable — text tracks will use the default renderer")
                null
            }
        }.onFailure { Timber.tag(TAG).e(it, "Failed to init libass for FCast inbound") }
            .getOrNull()
        libassRenderer = renderer

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val sink = checkNotNull(
                    super.buildAudioSink(
                        context,
                        enableFloatOutput,
                        enableAudioTrackPlaybackParams,
                    ),
                )
                return TimingAudioSink(sink).also { audioSinkTimingProbe = it }
            }

            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                val active = renderer
                if (active != null) {
                    // libass claims text tracks exclusively for the formats it supports.
                    // We add it first so it gets priority for ASS/SRT/VTT.
                    val fontLoader: () -> List<Pair<String, ByteArray>> = {
                        runCatching {
                            runBlocking {
                                libassFontsDeferred?.await().orEmpty()
                            }
                        }.getOrDefault(emptyList())
                    }
                    out.add(
                        LibassTextRenderer(
                            libassRenderer = active,
                            onTrackInitialized = {},
                            fontLoader = fontLoader,
                            usagePref = "auto",
                        )
                    )
                    Timber.tag(TAG).i("FCast inbound: LibassTextRenderer registered")
                }
                // Always add ExoPlayer's default renderers so we don't lose support for PGS,
                // TTML, tx3g, and other formats that libass doesn't handle.
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                out.filterIsInstance<androidx.media3.exoplayer.text.TextRenderer>().forEach {
                    it.experimentalSetLegacyDecodingEnabled(true)
                    val index = out.indexOf(it)
                    if (index != -1) {
                        out[index] = dev.jdtech.jellyfin.player.core.FallbackTextRenderer(it)
                    }
                }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    /**
     * Single ~60 Hz loop that pumps the libass renderer and updates the on-screen overlay
     * bitmap. Skipped when there is no libass renderer or when we're in split-A/V audio mode
     * (no video to overlay onto, and the audio receiver renders no subs by design).
     */
    private fun startSubtitleRenderLoop() {
        subtitleRenderJob?.cancel()
        subtitleRenderJob = lifecycleScope.launch {
            val r = libassRenderer ?: return@launch
            while (isActive) {
                val p = player
                if (p == null || splitAvRole == SplitAvRole.AUDIO) {
                    libassBitmapState.value = null
                    delay(120L)
                    continue
                }
                // libass keeps the parsed track in native memory, so deselecting the text track
                // ("None") stops new samples but the renderer keeps drawing the retained track.
                // Gate on whether a text track is actually selected so captions can be turned off.
                // (Theater receiver — no PlayerViewModel/visualSubtitlesEnabled, so ExoPlayer's own
                // selection is the signal.)
                val subtitleSelected = p.currentTracks.groups.any {
                    it.type == C.TRACK_TYPE_TEXT && it.isSelected
                }
                if (!subtitleSelected) {
                    if (libassBitmapState.value != null) libassBitmapState.value = null
                    delay(120L)
                    continue
                }
                val result = r.renderFrame(p.currentPosition)
                if (result.hasContent && result.bitmap != null) {
                    libassBitmapState.value = result.bitmap
                } else {
                    libassBitmapState.value = null
                }
                delay(16L) // ~60 fps for karaoke / \move / \fad smoothness
            }
        }
    }

    /**
     * Re-route a subsequent inbound Play frame into the same Activity instance. Because the
     * Activity is `launchMode="singleTask"` + `FLAG_ACTIVITY_CLEAR_TOP`, the second Play (the
     * real media URL that follows the calibration WAV) gets delivered here rather than
     * re-running [onCreate]. Without this override the Activity would keep playing the old
     * (calibration / previous-cast) URL and the user would hear nothing for the real media.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    /**
     * Read the serialized external request from [newIntent] and (re)configure ExoPlayer and the
     * audio-only overlay accordingly. Returns false if no valid request is present; caller is
     * responsible for finishing in that case (only relevant from [onCreate]; an in-session
     * malformed intent just leaves the previous media playing).
     */
    private fun applyIntent(newIntent: Intent): Boolean {
        val request = ExternalStreamIntentCodec.getRequest(newIntent)
        if (request == null) {
            Timber.w("FCastInboundPlayerActivity intent without valid external request")
            return false
        }
        // Kick off the embedded-font fetch ASAP — the LibassTextRenderer's font loader closure
        // blocks on the resulting deferred at track init, which happens after ExoPlayer
        // resolves the manifest. That's typically 200ms+ from now, which is enough lead time
        // for a healthy Jellyfin server to round-trip `/MediaSources`. On slow networks the
        // closure still blocks but only the libass renderer thread sees that, never the UI.
        preloadLibassFontsAsync((request.source as? ExternalStreamSource.Url)?.url)
        val title = request.title
        val newSplitAvRole = request.splitAv?.role
        val splitAvCadenceHz = request.splitAv?.syncCadenceHz
        splitAvRole = newSplitAvRole
        preferredAudioLanguage = request.preferredAudioLanguage
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        preferredAudioLanguageApplied = false
        audioSinkPositionOffsetUs = null
        playbackTickerIntervalMs = if (newSplitAvRole != null) {
            val hz = splitAvCadenceHz ?: SplitAvMetadata.DEFAULT_SYNC_CADENCE_HZ
            (1_000L / hz.coerceAtLeast(1)).coerceAtLeast(MIN_TICKER_INTERVAL_MS)
        } else {
            NORMAL_TICKER_INTERVAL_MS
        }

        val exo = player ?: return false

        titleState.value = title
        thumbnailUrlState.value = request.thumbnailUrl

        // SpatialFin audio-info extension: render the sender-reported source codec and
        // transcoded flag below the title so the user knows whether the receiver is
        // direct-playing the original audio or playing a JF-rewritten AAC fallback.
        audioInfoLineState.value = formatSourceAudioInfo(request.sourceAudioCodec, request.audioTranscoded)

        if (newSplitAvRole == SplitAvRole.AUDIO) {
            exo.trackSelectionParameters = TrackSelectionParameters.Builder()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            isAudioOnlyState.value = true
        } else {
            // Non-split path: clear any previous overlay state (e.g. when an earlier session
            // disabled video tracks for a calibration WAV).
            exo.trackSelectionParameters = TrackSelectionParameters.Builder().build()
            isAudioOnlyState.value = false
        }
        Timber.i(
            "FCast inbound: source=%s role=%s ticker=%dms",
            request.source::class.simpleName, newSplitAvRole?.name ?: "fullAv", playbackTickerIntervalMs,
        )

        // Re-tune ExoPlayer to the new media. Force playWhenReady=false before prepare so a
        // prior playing split/calibration stream cannot auto-start the replacement before the
        // sender has issued its coordinated Resume/ResumeAt command.
        cancelScheduledResume()
        exo.playWhenReady = false
        exo.pause()
        exo.stop()
        runCatching { mediaPreparer.replace(exo, request) }
            .onFailure { Timber.tag(TAG).w(it, "Unable to prepare FCast inbound source") }
            .getOrElse { return false }
        // Split-A/V startup coordination: when we're the audio receiver, *load* the stream
        // but stay paused. The XR sender is still loading its own (video) master and is
        // multiple seconds behind us at this point. If we start playing now, the sender's
        // drift policy will see a huge negative drift the instant its master finishes
        // loading and either degrade or hard-seek the master forward into a buffer cycle.
        // The sender's pause-mirror sends Resume on the FCast wire as soon as its master
        // becomes playWhenReady=true — that's our cue to actually start audio. Until then
        // we stay at startMs with playWhenReady=false.
        if (newSplitAvRole == SplitAvRole.AUDIO) {
            exo.playWhenReady = false
        } else {
            exo.play()
        }

        FCastInboundSession.bindControl(control)
        startPlaybackTicker()
        return true
    }

    private fun applyPreferredAudioLanguageIfNeeded(tracks: Tracks) {
        val preferred = preferredAudioLanguage ?: return
        if (preferredAudioLanguageApplied) return
        val p = player ?: return
        val group = tracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_AUDIO &&
                group.isSupported &&
                (0 until group.length).any { index ->
                    val format = group.getTrackFormat(index)
                    languageMatches(format.language, preferred) ||
                        languageMatches(format.label, preferred)
                }
        } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        preferredAudioLanguageApplied = true
        Timber.tag(TAG).i("Applied preferred audio language %s to inbound receiver", preferred)
    }

    private fun startPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = lifecycleScope.launch {
            // 1s cadence is enough to keep the sender's seek bar coherent without spamming
            // the wire. Sender-side seekBy() reads `remoteState.time` so this also keeps
            // ±10s skips accurate without forcing the sender to round-trip a request first.
            // In split-A/V audio mode the cadence is ~10x higher because the sender's drift
            // correction loop drives video timing off these beacons.
            while (isActive) {
                pushPlaybackSnapshot()
                delay(playbackTickerIntervalMs)
            }
        }
    }

    private fun pushPlaybackSnapshot() {
        val p = player ?: return
        val playerPositionMs = p.currentPosition.coerceAtLeast(0L)
        val durationMs = p.duration.takeIf { it > 0L }
        val audioTiming = normalizeAudioTiming(
            raw = audioSinkTimingProbe?.snapshot(),
            playerPositionMs = playerPositionMs,
            durationMs = durationMs,
        )
        val preferredPositionMs = audioTiming?.positionUs?.div(1_000L) ?: playerPositionMs
        val preferredSampleMs = audioTiming?.sampleMonotonicMs ?: SystemClock.elapsedRealtime()
        // Report user *intent* (playWhenReady), not transient runtime state (isPlaying). A
        // brief buffering moment (chunk fetch, decoder warm-up after a seek) flips isPlaying
        // false but does not change the user's intent to play. In a split-A/V session the
        // sender's drift controller cascades any "Paused" beacon back through
        // pauseFromMaster() to the XR master — which then pauses the master, which the
        // mirror cascades back to us as a real pause, deadlocking the session every time the
        // Pixel hits a buffer chunk. Pause only when the user (or a programmatic mirror
        // resulting from one) has actually toggled playWhenReady=false.
        val state = when {
            !p.playWhenReady -> PlaybackState.Paused
            p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE ->
                PlaybackState.Idle
            else -> PlaybackState.Playing
        }
        val duration = durationMs?.let { it / 1000.0 }
        // Probe the currently-selected ExoPlayer audio track and ship it as a SpatialFin
        // beacon extension. Non-SpatialFin senders ignore the field (kotlinx.serialization
        // `ignoreUnknownKeys = true`); the sender-side StateFlow on CastSessionManager holds
        // the last-seen value so the mini-controller can render "Dolby Atmos · 7.1" without
        // re-probing on every beacon. Null until a track is selected; ExoPlayer typically
        // resolves track info within ~200 ms of `prepare()`.
        FCastInboundSession.pushPlaybackUpdate(
            PlaybackUpdateMessage(
                generationTime = System.currentTimeMillis(),
                state = state.code,
                time = preferredPositionMs / 1000.0,
                duration = duration,
                speed = p.playbackParameters.speed.toDouble(),
                audioFormat = AudioFormatProbe.probe(p),
                // v4: monotonic stamp of the position sample, in the *same* clock as the
                // NTP Ping/Pong, so the sender maps this beacon precisely via offset θ.
                monotonicSampleMs = preferredSampleMs,
                // Authoritative audio-codec capability of this chain → drives the sender's
                // split-A/V direct-stream vs transcode decision (capability-driven, not a
                // hardcoded table, so it is correct on any AVR/soundbar/TV).
                supportedAudioCodecs = receiverAudioCodecs.takeIf { it.isNotEmpty() },
                audioSinkPositionUs = audioTiming?.positionUs,
                audioSinkSampleMonotonicMs = audioTiming?.sampleMonotonicMs,
                audioSinkBufferSizeUs = audioTiming?.bufferSizeUs,
                videoReadyToStart = if (splitAvRole == SplitAvRole.AUDIO) {
                    p.playbackState == Player.STATE_READY
                } else {
                    null
                },
                bufferedPositionMs = p.bufferedPosition.takeIf { it >= 0L },
                audioRoute = receiverAudioRouteInfo,
            )
        )
    }

    @OptIn(UnstableApi::class)
    private fun normalizeAudioTiming(
        raw: TimingAudioSink.Snapshot?,
        playerPositionMs: Long,
        durationMs: Long?,
    ): TimingAudioSink.Snapshot? {
        if (raw == null || raw.positionUs < 0L) return null
        val playerPositionUs = playerPositionMs.coerceAtLeast(0L) * 1_000L
        val existingOffsetUs = audioSinkPositionOffsetUs
        var normalizedUs = if (existingOffsetUs == null) {
            val directPositionMs = raw.positionUs / 1_000L
            val offsetUs =
                if (
                    ReceiverAudioClock.isPlausiblePositionMs(directPositionMs, durationMs) &&
                    abs(directPositionMs - playerPositionMs) <=
                    ReceiverAudioClock.MAX_AUDIO_SINK_DIVERGENCE_MS
                ) {
                    0L
                } else {
                    raw.positionUs - playerPositionUs
                }
            audioSinkPositionOffsetUs = offsetUs
            raw.positionUs - offsetUs
        } else {
            raw.positionUs - existingOffsetUs
        }

        val normalizedMs = normalizedUs / 1_000L
        if (
            !ReceiverAudioClock.isPlausiblePositionMs(normalizedMs, durationMs) ||
            abs(normalizedMs - playerPositionMs) >
            ReceiverAudioClock.MAX_AUDIO_SINK_DIVERGENCE_MS
        ) {
            audioSinkPositionOffsetUs = raw.positionUs - playerPositionUs
            normalizedUs = playerPositionUs
        }

        return raw.copy(positionUs = normalizedUs.coerceAtLeast(0L))
    }

    private fun pushTracksSnapshot() {
        val p = player ?: return
        val audioTracks = mutableListOf<dev.jdtech.jellyfin.fcast.protocol.SpatialFinTrack>()
        val subtitleTracks = mutableListOf<dev.jdtech.jellyfin.fcast.protocol.SpatialFinTrack>()
        for (i in p.currentTracks.groups.indices) {
            val group = p.currentTracks.groups[i]
            if (!group.isSupported) continue
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue
            val format = group.getTrackFormat(0)
            val id = i.toString()
            val language = format.language
            val label = format.label ?: language ?: "Track $i"
            val isSelected = group.isSelected
            val track = dev.jdtech.jellyfin.fcast.protocol.SpatialFinTrack(id, label, language, isSelected)
            if (type == C.TRACK_TYPE_AUDIO) audioTracks.add(track)
            if (type == C.TRACK_TYPE_TEXT) subtitleTracks.add(track)
        }
        FCastInboundSession.pushTracksUpdate(
            dev.jdtech.jellyfin.fcast.protocol.SpatialFinTracksUpdateMessage(audioTracks, subtitleTracks)
        )
    }

    /**
     * Build the receiver-side audio-info line from sender-reported metadata. Returns null when
     * neither codec nor transcoded flag is known — caller hides the view in that case so
     * non-SpatialFin senders don't see an empty row. Examples:
     *   - codec=eac3, transcoded=false → "Source · E-AC-3 · Direct play"
     *   - codec=truehd, transcoded=true → "Source · TrueHD · Transcoded"
     *   - codec=null, transcoded=true → "Audio · Transcoded"
     */
    private fun formatSourceAudioInfo(codec: String?, transcoded: Boolean?): String? {
        if (codec == null && transcoded == null) return null
        val pretty = codec?.let { prettyAudioCodec(it) }
        return when {
            pretty != null && transcoded == true -> "Source · $pretty · Transcoded"
            pretty != null && transcoded == false -> "Source · $pretty · Direct play"
            pretty != null -> "Source · $pretty"
            transcoded == true -> "Audio · Transcoded"
            transcoded == false -> "Audio · Direct play"
            else -> null
        }
    }

    private fun prettyAudioCodec(codec: String): String = when (codec.lowercase()) {
        "eac3", "ec3" -> "E-AC-3"
        "ac3" -> "AC-3"
        "truehd" -> "TrueHD"
        "dts" -> "DTS"
        "dts-hd", "dtshd" -> "DTS-HD"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "mp3" -> "MP3"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "pcm", "pcm_s16le", "pcm_s24le" -> "PCM"
        else -> codec.uppercase()
    }

    private fun languageMatches(candidate: String?, preferred: String?): Boolean {
        val normalizedCandidate = normalizeLanguage(candidate) ?: return false
        val normalizedPreferred = normalizeLanguage(preferred) ?: return false
        return normalizedCandidate == normalizedPreferred
    }

    private fun normalizeLanguage(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when {
            normalized == "ja" || normalized == "jpn" || normalized.startsWith("ja-") ||
                normalized.contains("japanese") -> "jpn"
            normalized == "en" || normalized == "eng" || normalized.startsWith("en-") ||
                normalized.contains("english") -> "eng"
            else -> normalized
        }
    }

    override fun onStop() {
        super.onStop()
        if (splitAvRole == SplitAvRole.AUDIO) {
            Timber.tag(TAG).i("onStop: keeping split-A/V audio receiver alive")
        } else {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackTickerJob?.cancel()
        playbackTickerJob = null
        subtitleRenderJob?.cancel()
        subtitleRenderJob = null
        cancelScheduledResume()
        FCastInboundSession.unbindControl(control)
        // Tell the sender the stream is over so its mini-controller drops back to Idle.
        FCastInboundSession.pushPlaybackUpdate(
            PlaybackUpdateMessage(
                generationTime = System.currentTimeMillis(),
                state = PlaybackState.Idle.code,
                time = (player?.currentPosition ?: 0L) / 1000.0,
            )
        )
        player?.removeListener(playerListener)
        player?.release()
        player = null
        libassRenderer?.destroy()
        libassRenderer = null
    }

    @OptIn(UnstableApi::class)
    private class TimingAudioSink(delegate: AudioSink) : ForwardingAudioSink(delegate) {
        data class Snapshot(
            val positionUs: Long,
            val sampleMonotonicMs: Long,
            val bufferSizeUs: Long?,
        )

        @Volatile
        private var latest: Snapshot? = null

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val positionUs = super.getCurrentPositionUs(sourceEnded)
            if (positionUs != AudioSink.CURRENT_POSITION_NOT_SET) {
                latest = Snapshot(
                    positionUs = positionUs,
                    sampleMonotonicMs = SystemClock.elapsedRealtime(),
                    bufferSizeUs = runCatching { getAudioTrackBufferSizeUs() }
                        .getOrNull()
                        ?.takeIf { it > 0L },
                )
            }
            return positionUs
        }

        fun snapshot(): Snapshot? = latest

        override fun flush() {
            latest = null
            super.flush()
        }

        override fun reset() {
            latest = null
            super.reset()
        }

        override fun release() {
            latest = null
            super.release()
        }
    }

    companion object {
        /** Upper bound on how long a v4 synchronized-start may defer playback. Beyond this
         *  the receiver resumes immediately — a scheduled start should be a few hundred ms
         *  out, so a larger value means a bad offset estimate; resume-now is the safe floor. */
        private const val MAX_SCHEDULED_START_WAIT_MS: Long = 4_000L

        private const val NORMAL_TICKER_INTERVAL_MS: Long = 1_000L
        private const val MIN_TICKER_INTERVAL_MS: Long = 50L
        private const val REMOTE_SEEK_BACK_MS: Long = 10_000L
        private const val REMOTE_SEEK_FORWARD_MS: Long = 10_000L
        private const val TAG: String = "FCastInbound"

        /**
         * Matches `/Videos/<uuid>/stream` and the alternates Jellyfin actually serves
         * (`/Videos/<uuid>/<sessionId>/stream`, `/Videos/<uuid>/main.m3u8`, etc.). The first
         * captured UUID is the item id.
         */
        private val JELLYFIN_VIDEO_PATH_REGEX = Regex(
            "/Videos/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/",
        )

        fun createIntent(
            context: Context,
            request: ExternalStreamRequest,
        ): Intent = Intent(context, FCastInboundPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ExternalStreamIntentCodec.putRequest(this, request)
        }
    }
}
