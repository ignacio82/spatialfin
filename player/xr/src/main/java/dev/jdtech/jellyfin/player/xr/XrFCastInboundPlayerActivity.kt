package dev.jdtech.jellyfin.player.xr

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.session.MediaSession
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.scenecore.scene
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.SpatialFinTrack
import dev.jdtech.jellyfin.fcast.protocol.SpatialFinTracksUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamIntentCodec
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamSource
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundBridgeIpcClient
import android.net.Uri
import androidx.media3.common.MediaItem
import dev.jdtech.jellyfin.player.core.audio.AudioPassthroughSinks
import dev.jdtech.jellyfin.player.core.external.ExternalStreamMediaPreparer
import dev.jdtech.jellyfin.player.local.presentation.PlayerTrackHeuristics
import dev.jdtech.jellyfin.settings.language.LanguageCatalog
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.MediaStreamType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Focused Full Space FCast receiver. It deliberately owns no library view model or Jellyfin
 * session state: external media is transient and exposes playback controls only.
 */
@AndroidEntryPoint
class XrFCastInboundPlayerActivity : AppCompatActivity() {
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var repository: JellyfinRepository

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var xrSession: Session? = null
    private var bridge: FCastInboundBridgeIpcClient? = null
    private var ticker: Job? = null
    private var subtitleTicker: Job? = null
    private var libassRenderer: LibassRenderer? = null
    private var libassFontsDeferred: CompletableDeferred<List<Pair<String, ByteArray>>>? = null
    private var finishRequested = false
    private var usedSurfaceEntity = false
    private val requestState = MutableStateFlow<ExternalStreamRequest?>(null)
    private val cuesState = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    private val libassBitmapState = MutableStateFlow<Bitmap?>(null)
    private val libassFrameVersionState = MutableStateFlow(0)
    private val mediaPreparer by lazy {
        ExternalStreamMediaPreparer(this, parseSubtitlesDuringExtraction = false)
    }

    private val resumeHandler = Handler(Looper.getMainLooper())
    private var scheduledResume: Runnable? = null

    private fun cancelScheduledResume() {
        scheduledResume?.let(resumeHandler::removeCallbacks)
        scheduledResume = null
    }

    private val control = object : ExternalStreamPlayer {
        override fun play(request: ExternalStreamRequest): ExternalStreamPlayer.PlayResult =
            ExternalStreamPlayer.PlayResult.Rejected("Play requests launch or replace the immersive Activity")

        override fun pause() = runOnUiThread {
            cancelScheduledResume()
            player?.pause()
            pushPlaybackSnapshot()
        }

        override fun resume() = runOnUiThread {
            cancelScheduledResume()
            player?.play()
            pushPlaybackSnapshot()
        }

        override fun resumeAt(atReceiverMonotonicMs: Long) = runOnUiThread {
            cancelScheduledResume()
            val delayMs = atReceiverMonotonicMs - SystemClock.elapsedRealtime()
            if (delayMs <= 0L || delayMs > MAX_SCHEDULED_START_WAIT_MS) {
                player?.play()
            } else {
                Runnable {
                    player?.play()
                    pushPlaybackSnapshot()
                    scheduledResume = null
                }.also {
                    scheduledResume = it
                    resumeHandler.postDelayed(it, delayMs)
                }
            }
        }

        override fun stop() = runOnUiThread { requestFinish("sender-stop") }

        override fun seek(seconds: Double) = runOnUiThread {
            libassRenderer?.clearCache()
            player?.seekTo((seconds * 1000.0).toLong().coerceAtLeast(0L))
        }

        override fun setVolume(volume: Double) = runOnUiThread {
            player?.volume = volume.toFloat().coerceIn(0f, 1f)
        }

        override fun setSpeed(speed: Double) = runOnUiThread {
            player?.setPlaybackSpeed(speed.toFloat().coerceIn(0.25f, 4f))
        }

        override fun setTrack(type: Int, trackId: String) = runOnUiThread {
            val exo = player ?: return@runOnUiThread
            // A manual pick from the sender wins over the smart default for this item.
            exo.currentMediaItem?.mediaId?.let { smartSubtitleAppliedForItem = it }
            // Sentinel "off" disables the track type (the sender's "Off" subtitle row).
            // Previously there was no way to disable a track type at all.
            if (trackId == "off" || trackId == "-1") {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, true)
                    .build()
                return@runOnUiThread
            }
            val groupIndex = trackId.toIntOrNull() ?: return@runOnUiThread
            val group = exo.currentTracks.groups.getOrNull(groupIndex) ?: return@runOnUiThread
            if (group.type != type) return@runOnUiThread
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(type)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
                .setTrackTypeDisabled(type, false)
                // Honour the explicit pick over a sibling track's DEFAULT/FORCED flag.
                .apply { if (type == C.TRACK_TYPE_TEXT) setIgnoredTextSelectionFlags(0) }
                .build()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushPlaybackSnapshot()
        override fun onPlaybackStateChanged(playbackState: Int) = pushPlaybackSnapshot()
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) =
            pushPlaybackSnapshot()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                libassRenderer?.clearCache()
            }
        }
        override fun onVolumeChanged(volume: Float) {
            bridge?.publish(VolumeUpdateMessage(System.currentTimeMillis(), volume.toDouble()))
        }
        override fun onTracksChanged(tracks: Tracks) {
            applySmartSubtitleSelection(tracks)
            pushTracksSnapshot(tracks)
        }
        override fun onCues(cueGroup: CueGroup) {
            cuesState.value = cueGroup.cues
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val request = ExternalStreamIntentCodec.getRequest(intent)
        if (request == null) {
            finish()
            return
        }

        val exo = buildPlayerWithLibass().also {
            player = it
            it.addListener(playerListener)
        }

        val result = runCatching { Session.create(this) }.getOrNull()
        if (result !is SessionCreateSuccess) {
            Timber.tag(TAG).w("XR session unavailable; forwarding incoming cast to flat player")
            forwardToFlatPlayer(request)
            exo.release()
            player = null
            finish()
            return
        }
        xrSession = result.session
        runCatching { result.session.scene.requestFullSpace() }

        bridge = FCastInboundBridgeIpcClient(this, control).also { it.connect() }
        mediaSession = MediaSession.Builder(this, exo).build()
        setContent {
            XrFCastInboundPlayerScreen(
                session = result.session,
                player = exo,
                requestState = requestState,
                cuesState = cuesState,
                libassBitmapState = libassBitmapState,
                libassFrameVersionState = libassFrameVersionState,
                libassEnabled = libassRenderer != null,
                preferences = appPreferences,
                onSurfaceAttached = { usedSurfaceEntity = true },
                onExit = { requestFinish("user-exit") },
                onSubtitleResize = { rw, rh, sw, sh ->
                    libassRenderer?.resize(rw, rh, sw, sh)
                },
            )
        }
        applyRequest(request)
        ticker = lifecycleScope.launch {
            while (isActive) {
                pushPlaybackSnapshot()
                delay(1_000L)
            }
        }
        subtitleTicker = lifecycleScope.launch {
            while (isActive) {
                // libass keeps the parsed track in native memory, so deselecting the text track in
                // the Media3 dialog ("None") stops new samples but the renderer keeps drawing the
                // retained track. Gate the overlay on whether a text track is actually selected so
                // captions can be turned off. This player has no PlayerViewModel/visualSubtitlesEnabled
                // flag (theater-only), so ExoPlayer's own selection is the signal.
                val subtitleSelected = exo.currentTracks.groups.any {
                    it.type == C.TRACK_TYPE_TEXT && it.isSelected
                }
                val rendered =
                    if (subtitleSelected) libassRenderer?.renderFrame(exo.currentPosition) else null
                val newBitmap = rendered?.bitmap?.takeIf { rendered.hasContent }
                if (newBitmap != null) {
                    libassBitmapState.value = newBitmap
                    libassFrameVersionState.value += 1
                } else if (libassBitmapState.value != null) {
                    libassBitmapState.value = null
                }
                delay(16L)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ExternalStreamIntentCodec.getRequest(intent)?.let(::applyRequest)
    }

    private fun applyRequest(request: ExternalStreamRequest) {
        val exo = player ?: return
        smartSubtitleAppliedForItem = null
        requestState.value = request
        preloadLibassFontsAsync((request.source as? ExternalStreamSource.Url)?.url)
        runCatching { mediaPreparer.replace(exo, request) }
            .onFailure {
                Timber.tag(TAG).w(it, "Could not prepare immersive inbound source")
                requestFinish("prepare-failed")
            }
            .onSuccess {
                exo.play()
                pushPlaybackSnapshot()
                sideloadJellyfinSubtitlesAsync(request)
            }
    }

    private var subtitleSideloadJob: kotlinx.coroutines.Job? = null

    /**
     * Jellyfin delivers a title's subtitle tracks as separate `/Videos/{id}/Subtitles/...`
     * deliveryUrls, not inside the (often transcoded, single-track) cast stream — so a cast
     * receiver that just plays the stream URL sees only the one embedded subtitle. This fetches
     * the source's full subtitle list (exactly like the local player's PlaylistManager) and
     * re-prepares the player with them sideloaded as [MediaItem.SubtitleConfiguration]s,
     * preserving the current position. Resets the smart-subtitle guard so the forced-only
     * default re-evaluates against the now-complete subtitle list.
     */
    private fun sideloadJellyfinSubtitlesAsync(request: ExternalStreamRequest) {
        subtitleSideloadJob?.cancel()
        val url = (request.source as? ExternalStreamSource.Url)?.url
        val itemId = url?.let(::extractJellyfinItemId) ?: return
        subtitleSideloadJob = lifecycleScope.launch {
            val subs = withContext(Dispatchers.IO) {
                runCatching {
                    // Force a direct-play-bitrate profile so the server returns subtitle tracks
                    // as External (with a deliveryUrl) rather than marking them for burn-in /
                    // transcode (no URL). A null/low cap made Jellyfin omit the deliveryUrl, so
                    // every subtitle was dropped by the `path` filter below.
                    repository.getMediaSources(itemId, includePath = true, maxBitrate = 1_000_000_000L)
                        .flatMap { it.mediaStreams }
                        .filter { it.type == MediaStreamType.SUBTITLE && !it.path.isNullOrBlank() }
                        .distinctBy { it.path }
                        .map { stream ->
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(stream.path))
                                .setMimeType(subtitleMimeType(stream.codec))
                                .setLanguage(stream.language.ifBlank { null })
                                .setLabel(stream.title.ifBlank { stream.language.ifBlank { "Subtitle" } })
                                .build()
                        }
                }.onFailure {
                    Timber.tag(TAG).w(it, "FCast inbound subtitle sideload failed")
                }.getOrDefault(emptyList())
            }
            if (subs.isEmpty()) return@launch
            val exo = player ?: return@launch
            // Re-prepare with subtitles at the current position; reset the smart guard so the
            // forced-subtitle default re-runs against the full list (the forced track is usually
            // one of the sideloaded ones).
            val position = exo.currentPosition
            val wasPlaying = exo.playWhenReady
            smartSubtitleAppliedForItem = null
            runCatching { mediaPreparer.replace(exo, request, subs, startPositionMs = position) }
                .onSuccess {
                    exo.playWhenReady = wasPlaying
                    Timber.tag(TAG).i("FCast inbound: sideloaded %d subtitle tracks", subs.size)
                }
                .onFailure { Timber.tag(TAG).w(it, "FCast inbound subtitle re-prepare failed") }
        }
    }

    private fun subtitleMimeType(codec: String): String = when (codec.lowercase()) {
        "subrip", "srt" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        "webvtt", "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
        "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
        else -> androidx.media3.common.MimeTypes.TEXT_UNKNOWN
    }

    private fun pushPlaybackSnapshot() {
        val exo = player ?: return
        val state = when {
            !exo.playWhenReady -> PlaybackState.Paused
            exo.playbackState == Player.STATE_ENDED || exo.playbackState == Player.STATE_IDLE ->
                PlaybackState.Idle
            else -> PlaybackState.Playing
        }
        bridge?.publish(
            PlaybackUpdateMessage(
                generationTime = System.currentTimeMillis(),
                state = state.code,
                time = exo.currentPosition / 1000.0,
                duration = exo.duration.takeIf { it > 0 }?.div(1000.0),
                speed = exo.playbackParameters.speed.toDouble(),
                monotonicSampleMs = SystemClock.elapsedRealtime(),
            ),
        )
    }

    private fun pushTracksSnapshot(tracks: Tracks) {
        val audio = mutableListOf<SpatialFinTrack>()
        val subtitles = mutableListOf<SpatialFinTrack>()
        tracks.groups.forEachIndexed { index, group ->
            // Enumerate every audio + text track. Previously unsupported audio groups were
            // dropped, which hid alternate audio tracks the user could see in other players
            // (e.g. a second language track the XR can't hardware-decode but the server can
            // transcode). Text is always kept. Other track types (video, image, …) are skipped.
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) {
                return@forEachIndexed
            }
            val format = group.getTrackFormat(0)
            val track = SpatialFinTrack(
                id = index.toString(),
                name = format.label ?: format.language ?: "Track $index",
                language = format.language,
                isSelected = group.isSelected,
            )
            if (group.type == C.TRACK_TYPE_AUDIO) audio += track else subtitles += track
        }
        bridge?.publish(SpatialFinTracksUpdateMessage(audio, subtitles))
    }

    private var smartSubtitleAppliedForItem: String? = null

    /**
     * Applies the same smart-subtitle default the local players use
     * ([dev.jdtech.jellyfin.player.local.presentation.PlayerTrackSelector]) to a cast
     * session, so the FCast receiver stops auto-enabling a full foreign-language subtitle
     * the viewer doesn't want. Policy: when the playing audio is in a language the viewer
     * understands (their smart "spoken languages"), full dialogue subtitles stay OFF and
     * only a *forced / signs-only* track in that same language is enabled (translating just
     * the foreign-language portions). The default ExoPlayer selection would instead land on
     * a `DEFAULT`-flagged full English track. Manual picks from the sender win (see
     * [bridge]'s `setTrack`). Runs once per media item.
     */
    private fun applySmartSubtitleSelection(tracks: Tracks) {
        val exo = player ?: return
        val mediaId = exo.currentMediaItem?.mediaId ?: return
        if (smartSubtitleAppliedForItem == mediaId) return

        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (audioGroups.isEmpty()) return
        val subtitleGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        // Subtitle groups can arrive in a later onTracksChanged than audio; wait for them so we
        // don't lock in "no subtitle" before a forced track has even appeared.
        if (subtitleGroups.isEmpty()) return

        val spokenLanguages = appPreferences.getSmartSpokenLanguageCodes(this)
        val selectedAudio = audioGroups.firstOrNull { it.isSelected } ?: audioGroups.first()
        val audioLanguage = groupPrimaryLanguage(selectedAudio)
        // No language tag ⇒ assume understood (mirrors PlayerTrackSelector) so we don't force
        // full subs onto an untagged English track.
        val audioUnderstood = audioLanguage == null ||
            spokenLanguages.any { LanguageCatalog.matches(this, audioLanguage, it) }

        // Viewer doesn't understand the audio: leave ExoPlayer's default selection (a full
        // dialogue track is appropriate here). Mark applied so we don't keep re-running.
        if (!audioUnderstood) {
            smartSubtitleAppliedForItem = mediaId
            return
        }

        val forced =
            if (appPreferences.getValue(appPreferences.smartForcedSubtitles)) {
                val forcedGroups = subtitleGroups.filter { PlayerTrackHeuristics.isForcedOrSignsOnly(it) }
                val targetLanguages = (listOfNotNull(audioLanguage) + spokenLanguages).filter { it.isNotBlank() }.distinct()
                var match: Tracks.Group? = null
                if (targetLanguages.isNotEmpty()) {
                    for (targetLang in targetLanguages) {
                        val matched = forcedGroups.filter { groupMatchesLanguage(it, targetLang) }
                        if (matched.isNotEmpty()) {
                            match = matched.maxByOrNull { PlayerTrackHeuristics.forcedSubtitlePriority(it) }
                            break
                        }
                    }
                }
                match ?: forcedGroups.filter { group ->
                    val lang = (0 until group.length).mapNotNull { group.getTrackFormat(it).language }.firstOrNull()
                    lang.isNullOrBlank() || lang.equals("und", ignoreCase = true)
                }.maxByOrNull { PlayerTrackHeuristics.forcedSubtitlePriority(it) }
            } else {
                null
            }

        val builder = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (forced != null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(forced.mediaTrackGroup, 0))
                .setIgnoredTextSelectionFlags(0)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        exo.trackSelectionParameters = builder.build()
        smartSubtitleAppliedForItem = mediaId
        Timber.tag(TAG).i(
            "FCast inbound smart subtitle: audioLang=%s understood=%b forced=%s",
            audioLanguage, audioUnderstood, forced?.getTrackFormat(0)?.label,
        )
    }

    private fun groupPrimaryLanguage(group: Tracks.Group): String? =
        (0 until group.length)
            .mapNotNull { i ->
                LanguageCatalog.normalize(
                    this,
                    group.getTrackFormat(i).language ?: group.getTrackFormat(i).label,
                )
            }
            .firstOrNull()

    private fun groupMatchesLanguage(group: Tracks.Group, languageCode: String): Boolean =
        (0 until group.length).any { i ->
            val format = group.getTrackFormat(i)
            LanguageCatalog.matches(this, format.language, languageCode) ||
                LanguageCatalog.matches(this, format.label, languageCode)
        }

    private fun forwardToFlatPlayer(request: ExternalStreamRequest) {
        val fallback = Intent().setClassName(this, "dev.spatialfin.fcast.FCastInboundPlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ExternalStreamIntentCodec.putRequest(fallback, request)
        runCatching { startActivity(fallback) }
            .onFailure { Timber.tag(TAG).e(it, "Flat inbound fallback launch failed") }
    }

    private fun buildPlayerWithLibass(): ExoPlayer {
        val usagePref = appPreferences.getValue(appPreferences.libassSubtitleUsage)
        val renderer = runCatching {
            if (usagePref != "never" && LibassRenderer.isAvailable()) {
                LibassRenderer(1920, 1080).apply { init() }
            } else {
                null
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to initialize inbound libass renderer") }
            .getOrNull()
        libassRenderer = renderer
        val renderersFactory = object : DefaultRenderersFactory(this) {
            // Same passthrough preference as the first-party players — an inbound cast is
            // still played by this device's audio chain. AUTO keeps Media3's default sink.
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                AudioPassthroughSinks.buildOverride(
                    context = context,
                    appPreferences = appPreferences,
                    enableFloatOutput = enableFloatOutput,
                    enableAudioTrackPlaybackParams = enableAudioTrackPlaybackParams,
                ) ?: checkNotNull(
                    super.buildAudioSink(
                        context,
                        enableFloatOutput,
                        enableAudioTrackPlaybackParams,
                    ),
                )

            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                val active = renderer
                if (active != null) {
                    out.add(
                        LibassTextRenderer(
                            libassRenderer = active,
                            onTrackInitialized = {},
                            fontLoader = {
                                runCatching { runBlocking { libassFontsDeferred?.await().orEmpty() } }
                                    .getOrDefault(emptyList())
                            },
                            usagePref = usagePref,
                            srtFontSize = appPreferences.getValue(appPreferences.xrSubtitleSize),
                        )
                    )
                }
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
            
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .experimentalParseSubtitlesDuringExtraction(false)

        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    private fun preloadLibassFontsAsync(url: String?) {
        val deferred = CompletableDeferred<List<Pair<String, ByteArray>>>().also {
            libassFontsDeferred = it
        }
        val itemId = url?.let(::extractJellyfinItemId)
        if (itemId == null) {
            deferred.complete(emptyList())
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fonts = runCatching {
                repository.getMediaSources(itemId, includePath = false)
                    .flatMap { source -> source.mediaAttachments.map { source to it } }
                    .filter { (_, attachment) ->
                        isFontAttachment(attachment.fileName, attachment.mimeType, attachment.codec)
                    }
                    .mapNotNull { (source, attachment) ->
                        val name = attachment.fileName.ifBlank { "attachment-${source.id}-${attachment.index}" }
                        repository.getMediaAttachment(itemId, source.id, attachment.index)?.let { name to it }
                    }
            }.onFailure { Timber.tag(TAG).w(it, "Embedded font load failed for immersive inbound playback") }
                .getOrDefault(emptyList())
            deferred.complete(fonts)
        }
    }

    private fun extractJellyfinItemId(url: String): UUID? =
        JELLYFIN_VIDEO_PATH_REGEX.find(url)?.groupValues?.get(1)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun isFontAttachment(fileName: String, mimeType: String, codec: String): Boolean {
        val name = fileName.lowercase()
        val mime = mimeType.lowercase()
        val value = codec.lowercase()
        return mime.contains("font") || mime.contains("truetype") || mime.contains("opentype") ||
            name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc") ||
            value.contains("ttf") || value.contains("otf")
    }

    private fun requestFinish(reason: String) {
        if (finishRequested || isFinishing) return
        finishRequested = true
        Timber.tag(TAG).i("Immersive inbound player finishing: %s", reason)
        finish()
    }

    override fun onPause() {
        super.onPause()
        killProcessIfFinishing("onPause")
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) player?.pause()
        killProcessIfFinishing("onStop")
    }

    override fun onDestroy() {
        ticker?.cancel()
        subtitleTicker?.cancel()
        cancelScheduledResume()
        bridge?.disconnect()
        bridge = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        libassRenderer?.destroy()
        libassRenderer = null
        xrSession = null
        super.onDestroy()
    }

    private fun killProcessIfFinishing(stage: String) {
        if (!isFinishing || !usedSurfaceEntity) return
        Timber.tag(TAG).w("Killing :xrplayer at %s after immersive inbound close", stage)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val TAG = "XrFCastInbound"
        private const val MAX_SCHEDULED_START_WAIT_MS = 4_000L
        // Case-insensitive: the Jellyfin stream URL the sender casts uses lowercase
        // `/videos/<id>/master.m3u8`, while subtitle/attachment deliveryUrls use `/Videos/`.
        // Matching only the capitalised form left itemId null on every cast, so neither the
        // embedded-font preload nor the subtitle sideload ever ran.
        private val JELLYFIN_VIDEO_PATH_REGEX = Regex(
            "/Videos/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/",
            RegexOption.IGNORE_CASE,
        )

        fun createIntent(context: Context, request: ExternalStreamRequest): Intent =
            Intent(context, XrFCastInboundPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ExternalStreamIntentCodec.putRequest(this, request)
            }
    }
}
