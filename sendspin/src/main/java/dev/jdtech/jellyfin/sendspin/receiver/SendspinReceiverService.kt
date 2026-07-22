package dev.jdtech.jellyfin.sendspin.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sendspin.protocol.ArtworkChannel
import com.sendspin.protocol.AudioChunk
import com.sendspin.protocol.ClientPreferences
import com.sendspin.protocol.ClientState
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.ControllerState
import com.sendspin.protocol.GroupPlaybackState
import com.sendspin.protocol.JsonOptional
import com.sendspin.protocol.JsonOptionalAdapterFactory
import com.sendspin.protocol.SendSpinClient
import com.sendspin.protocol.SendSpinServerHost
import com.sendspin.protocol.TrackMetadataMsg
import com.sendspin.protocol.VisualizerFrame
import com.sendspin.protocol.VisualizerSpectrumConfig
import com.sendspin.protocol.VisualizerSupport
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.jdtech.jellyfin.sendspin.R
import dev.jdtech.jellyfin.sendspin.discovery.SendspinReceiverAdvertiser
import dev.jdtech.jellyfin.sendspin.receiver.audio.AndroidSendspinAudioPlayer
import dev.jdtech.jellyfin.sendspin.receiver.remote.MaWebRtcConnection
import dev.jdtech.jellyfin.sendspin.receiver.remote.RemoteId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Foreground service that runs the SendSpin server-initiated receiver and advertises via mDNS.
 */
class SendspinReceiverService : Service() {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverHost: SendSpinServerHost? = null
    private var client: SendSpinClient? = null
    private var audioPlayer: AndroidSendspinAudioPlayer? = null
    private var okHttpClient: OkHttpClient? = null
    private var advertiser: SendspinReceiverAdvertiser? = null
    @Volatile private var bootstrapJob: Job? = null
    private val clientLifecycleLock = Any()

    /**
     * Per-client lifecycle scope: every collector and topology loop tied to the
     * current [SendSpinClient] instance lives here, so the stall watchdog can
     * tear the whole connection stack down (dead socket, poisoned clock-sync
     * filter) and rebuild it without leaking collectors from the old instance.
     */
    private var clientScope: CoroutineScope? = null
    private var watchdogJob: Job? = null
    private var musicAssistantClient: MusicAssistantGroupClient? = null
    private var musicAssistantRefreshJob: Job? = null

    /**
     * Off-LAN remote access: when a Music Assistant Remote ID is configured and no LAN
     * dial succeeds, the SendSpin protocol is tunnelled over a WebRTC data channel. The
     * connection drives the stock [SendSpinClient] through a loopback relay — see
     * [ensureWebRtcRemote] and the `remote/` package. Null when running LAN-direct.
     */
    @Volatile private var webRtcConnection: MaWebRtcConnection? = null
    @Volatile private var webRtcRemoteId: String? = null

    /**
     * When the WebRTC `ma-api` channel is up, this routes MA control JSON-RPC (player list,
     * group membership, play_media) over it instead of REST — making in-app MA control work
     * off-LAN. Applied to [musicAssistantClient] via [applyMaApiTransport].
     */
    @Volatile private var maApiTransport: MaRpcTransport? = null
    @Volatile private var maApiHttpProxy: dev.jdtech.jellyfin.sendspin.receiver.remote.MaWebRtcHttpProxy? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var displayName: String = "SpatialFin"
    private var receiverClientId: String = ""
    private var lastPort: Int = DEFAULT_PORT
    private var lastSoftwareVersion: String? = null
    private var smoothedVisualizerLevels: List<Float> = emptyList()
    @Volatile private var lastPlayedServerId: String = ""
    @Volatile private var lastClockRecoveryLogUs: Long = 0L

    /**
     * The current Jellyfin user, pushed in via Intent extras (the service is
     * decoupled from `:data` and can't read [JellyfinApi] directly). Scopes all
     * MA SharedPreferences reads/writes so each Jellyfin user keeps independent
     * Music Assistant config. Null ⇒ "default" scope (pre-login / local-only).
     */
    @Volatile private var currentJellyfinUserId: String? = null

    private fun scopedKey(base: String): String = scopedPrefKey(currentJellyfinUserId, base)

    /**
     * Set true whenever the connection (re)establishes, consumed by the first
     * playback-state emission that follows. It marks that emission as the
     * server's restore/catch-up sync rather than a user-initiated cast, so we
     * don't surface stale receiver controls on app launch when the previous
     * session's playback state is replayed.
     */
    @Volatile private var pendingConnectionSync: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireWifiLock()
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        try {
            @Suppress("DEPRECATION") // Same as FCast
            val lock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SpatialFinSendspinReceiver",
            ).apply { setReferenceCounted(false) }
            lock.acquire()
            wifiLock = lock
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Sendspin Wi-Fi lock permission denied")
        } catch (e: RuntimeException) {
            Timber.tag(TAG).w(e, "Sendspin Wi-Fi lock acquire failed")
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONTROLLER_COMMAND ||
            intent?.action == ACTION_DISMISS_CONTROLS ||
            intent?.action == ACTION_MUSIC_ASSISTANT_REFRESH ||
            intent?.action == ACTION_MUSIC_ASSISTANT_LOGIN ||
            intent?.action == ACTION_MUSIC_ASSISTANT_SAVE_TOKEN ||
            intent?.action == ACTION_MUSIC_ASSISTANT_SET_SERVER_URL ||
            intent?.action == ACTION_MUSIC_ASSISTANT_SET_REMOTE_ID ||
            intent?.action == ACTION_MUSIC_ASSISTANT_SET_MEMBER ||
            intent?.action == ACTION_MUSIC_ASSISTANT_PLAY_MEDIA ||
            intent?.action == ACTION_MUSIC_ASSISTANT_SET_USER
        ) {
            // Every MA intent self-describes the active Jellyfin user, so each
            // mutation scopes to the right user even if the dedicated SET_USER
            // rebind hasn't been delivered yet.
            if (intent.hasExtra(EXTRA_JELLYFIN_USER_ID)) {
                currentJellyfinUserId = intent.getStringExtra(EXTRA_JELLYFIN_USER_ID)
            }
            // MA REST works without the full SendSpin server (e.g. configuring MA
            // during first-run onboarding before the receiver bootstraps). Ensure
            // the REST client exists so a cold MA action doesn't hit a null client.
            ensureMusicAssistantClient()
            handleControllerCommand(intent)
            return START_STICKY
        }

        displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: displayName
        val clientId = intent?.getStringExtra(EXTRA_CLIENT_ID) ?: displayName
        receiverClientId = clientId
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val softwareVersion = intent?.getStringExtra(EXTRA_SOFTWARE_VERSION)
        lastPort = port
        lastSoftwareVersion = softwareVersion

        SendspinReceiverSession.update { it.copy(serviceRunning = true) }
        if (!startForegroundCompat()) {
            Timber.tag(TAG).w("SendspinReceiverService startForeground failed — stopping service")
            SendspinReceiverSession.update { it.copy(serviceRunning = false) }
            stopSelf()
            return START_NOT_STICKY
        }

        // `client` covers the outbound-only mode, where the listening host never
        // bound (serverHost == null) but a dialing client is alive and a second
        // start intent must not double-bootstrap.
        if (bootstrapJob?.isActive == true || serverHost != null || client != null) {
            return START_STICKY
        }
        bootstrapJob = scope.launch { startServer(displayName, clientId, port, softwareVersion) }
        return START_STICKY
    }

    private suspend fun startServer(
        displayName: String,
        clientId: String,
        port: Int,
        softwareVersion: String?,
    ) {
        val moshi =
            Moshi.Builder()
                .add(JsonOptionalAdapterFactory())
                .addLast(KotlinJsonAdapterFactory())
                .build()
        // pingInterval doubles as dead-connection detection: OkHttp fails the
        // WebSocket if a pong doesn't arrive within the interval. Without it a
        // Wi-Fi blip leaves the socket in STREAMING forever (writes just queue),
        // the dial loop sees "connected" and never redials, and MA keeps showing
        // the track as playing while this device is silent. The official MA app
        // pings every 5 s for exactly this reason.
        val http = OkHttpClient.Builder()
            .pingInterval(java.time.Duration.ofSeconds(WS_PING_INTERVAL_SECONDS))
            // Short connect timeout so an off-LAN dial to a stale URL fails fast and the
            // topology loop can fall back to the WebRTC remote tunnel within seconds rather
            // than hanging on the OkHttp default (10 s) per attempt. Loopback + MA REST use
            // their own clients/timeouts, so this only bounds the LAN /sendspin dial.
            .connectTimeout(java.time.Duration.ofSeconds(WS_DIAL_CONNECT_TIMEOUT_SECONDS))
            .build()
        // Dedicated client for MA REST: `player_queues/play_media` blocks until
        // the queue is loaded AND the wrapping universal-player has dispatched
        // audio to the underlying SendSpin endpoint. First-cast can take 15+ s;
        // OkHttp's default 10 s read timeout would surface as a misleading
        // "timeout" toast even though MA actually started playback. Bumping
        // here, not on the shared client, because SendSpin's WebSocket needs
        // its own timing profile.
        musicAssistantClient = ensureMusicAssistantClient(http)
        receiverClientId = clientId
        // Official-MA-app-style outbound dial: when this device already knows its
        // MA server (configured or previously used), we connect OUT to the
        // server's /sendspin endpoint instead of waiting for the server to
        // mDNS-discover our advert and dial in. Registration is instant (no
        // discovery wait, so the universal-player wrapper link survives across
        // plays) and the dial loop below re-establishes the connection within
        // seconds after a Wi-Fi blip — the inbound topology could stay silent for
        // minutes while MA showed the track as "playing". This snapshot only
        // gates the bind-failure decision; the topology loop re-resolves the
        // URL every tick so config that lands after bootstrap (per-user scoped
        // writes, onboarding, user switch) still upgrades us to outbound dial.
        val maOutboundUrl = currentOutboundDialUrl()
        val newClient =
            SendSpinClient(
                okHttpClient = http,
                moshi = moshi,
                preferences =
                    ClientPreferences(
                        supportedFormats = AndroidSendspinAudioPlayer.supportedFormats(),
                        artworkChannels = listOf(ArtworkChannel("album", "jpeg", 800, 800)),
                        visualizerSupport =
                            VisualizerSupport(
                                types = listOf("spectrum", "loudness", "peak"),
                                bufferCapacity = VISUALIZER_BUFFER_CAPACITY_BYTES,
                                rateMax = VISUALIZER_RATE_MAX,
                                spectrum =
                                    VisualizerSpectrumConfig(
                                        VISUALIZER_BIN_COUNT,
                                        "log",
                                        VISUALIZER_MIN_FREQUENCY_HZ,
                                        VISUALIZER_MAX_FREQUENCY_HZ,
                                    ),
                            ),
                    ),
                clientId = clientId,
                clientName = displayName,
                manufacturer = "SpatialFin",
                productName = android.os.Build.MODEL ?: "Android",
                softwareVersion = softwareVersion ?: "",
                audioPlayerFactory = { buffer, _ ->
                    AndroidSendspinAudioPlayer(buffer)
                },
                reconnectEnabled = false,
            )
        newClient.onVisualizerFrame = { frame ->
            updateVisualizerState(frame)
        }
        val clockRecoveryGuard =
            SendspinClockRecoveryGuard(
                requiredOutliers = CLOCK_RECOVERY_REQUIRED_OUTLIERS,
            )
        newClient.onAudioChunk = { chunk ->
            recoverSendspinClockIfNeeded(newClient, chunk, clockRecoveryGuard)
        }
        val createdAudioPlayer = newClient.audioPlayer as AndroidSendspinAudioPlayer
        newClient.setStaticDelayMs(ANDROID_AUDIO_OUTPUT_DELAY_MS)
        newClient.setRequiredLeadTimeMs(REQUIRED_LEAD_TIME_MS)
        newClient.setMinBufferMs(MIN_BUFFER_MS)

        val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        clientScope = cs

        val newHost = SendSpinServerHost(
            client = newClient,
            moshi = moshi,
            getLastPlayedServerId = { lastPlayedServerId },
            port = port,
            scope = cs,
        )
        var hostUsable = true
        try {
            newHost.startServer()
            withTimeout(10_000) { newHost.serverReady.await() }
        } catch (e: Exception) {
            if (maOutboundUrl == null) {
                Timber.tag(TAG).e(e, "Sendspin service failed to bind on port %d", port)
                createdAudioPlayer.close()
                runCatching { newHost.stopServer() }
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
                cs.cancel()
                clientScope = null
                stopSelf()
                return
            }
            // The outbound dial doesn't need a listening socket — keep the
            // service alive instead of going silent while MA reports playing.
            Timber.tag(TAG).w(e, "Sendspin host bind failed on port %d; continuing outbound-only", port)
            runCatching { newHost.stopServer() }
            hostUsable = false
        }
        client = newClient
        serverHost = newHost.takeIf { hostUsable }
        audioPlayer = createdAudioPlayer
        okHttpClient = http
        SendspinReceiverSession.update { it.copy(serviceRunning = true) }
        updateNotification()

        cs.launch {
            newClient.serverName.collect { name ->
                SendspinReceiverSession.update {
                    it.copy(serverName = name.takeIf(String::isNotBlank))
                }
                updateNotification()
            }
        }
        cs.launch {
            newClient.serverHello.collect { hello ->
                if (hello == null) {
                    clockRecoveryGuard.clearConnection()
                } else {
                    clockRecoveryGuard.beginConnection(
                        connectionToken = hello,
                        baseline = newClient.clockSync.recoveryReading(),
                    )
                }
            }
        }
        cs.launch {
            newClient.serverId.collect { id ->
                // A fresh connection: the next playback-state emission is the
                // server's restore sync, not a user cast — don't auto-show for it.
                if (id.isNotBlank()) pendingConnectionSync = true
                SendspinReceiverSession.update {
                    it.copy(
                        connected = id.isNotBlank(),
                        serverId = id.takeIf(String::isNotBlank),
                    )
                }
                if (id.isNotBlank()) {
                    refreshMusicAssistantPlayers(discoverServerUrl = true)
                } else {
                    musicAssistantRefreshJob?.cancel()
                    SendspinReceiverSession.update {
                        it.copy(
                            musicAssistantLoading = false,
                            musicAssistantError = null,
                            musicAssistantPlayers = emptyList(),
                            musicAssistantToken = null,
                            musicAssistantCurrentPlayerId = null,
                            musicAssistantQueuePlayerId = null,
                            musicAssistantGroupTargetPlayerId = null,
                            musicAssistantActiveMemberIds = emptySet(),
                            musicAssistantBusyPlayerIds = emptySet(),
                        )
                    }
                }
                updateNotification()
            }
        }
        cs.launch {
            newClient.groupPlaybackState.collect { state ->
                val playbackState = state ?: GroupPlaybackState.STOPPED
                if (playbackState == GroupPlaybackState.PLAYING) {
                    lastPlayedServerId = newClient.serverId.value
                }
                // The first emission after a (re)connect is the restore sync;
                // consume the guard so a replayed PLAYING state doesn't surface
                // stale receiver controls on launch. Subsequent starts are live.
                val isConnectionSync = pendingConnectionSync
                pendingConnectionSync = false
                SendspinReceiverSession.update {
                    val stopped = playbackState == GroupPlaybackState.STOPPED
                    val startingPlayback =
                        it.playbackState == SendspinReceiverPlaybackState.STOPPED && !stopped
                    // A genuine play start (not the post-reconnect restore sync)
                    // marks the session active, so the controls survive a later
                    // pause/stop instead of vanishing.
                    val genuineStart = startingPlayback && !isConnectionSync
                    it.copy(
                        playbackState = playbackState.toUiPlaybackState(),
                        playbackStarted = it.playbackStarted || genuineStart,
                        controlsDismissed =
                            when {
                                stopped -> it.controlsDismissed
                                // Restore sync replaying a playing state: keep
                                // receiver notification controls dismissed.
                                startingPlayback && isConnectionSync -> true
                                startingPlayback -> false
                                else -> it.controlsDismissed
                            },
                        visualizerLevels = it.visualizerLevels,
                    )
                }
                updateNotification()
            }
        }
        cs.launch {
            newClient.albumArtwork.collect { artwork ->
                updateAlbumArtwork(artwork)
                updateNotification()
            }
        }
        cs.launch {
            newClient.controllerState.collect { state ->
                if (state != null) {
                    createdAudioPlayer.setVolume(if (state.muted) 0 else state.volume)
                    updateControllerState(state)
                    updateNotification()
                }
            }
        }
        cs.launch {
            newClient.serverState.collect { state ->
                state.metadata?.let(::updateMetadataState)
                state.controller?.let { controller ->
                    createdAudioPlayer.setVolume(if (controller.muted) 0 else controller.volume)
                    updateControllerState(controller)
                }
                updateNotification()
            }
        }

        val ad = SendspinReceiverAdvertiser(applicationContext)
        advertiser = ad
        val advertiseProps = mapOf(
            "path" to "/sendspin",
            "name" to displayName,
            "manufacturer" to "SpatialFin",
            "model" to "Android",
        )
        // Keep the mDNS advert alive. A single register() right after a restart
        // can silently no-op (Wi-Fi interface not up yet → no bindable address,
        // or a jmDNS error), leaving the service running but invisible to MA —
        // which is exactly how MA "stops seeing" the receiver. Retry until it
        // registers, then health-check and re-publish if it ever drops, so MA
        // can always re-discover us without a manual restart.
        fun launchAdvertiseLoop() {
            cs.launch {
                while (true) {
                    // Re-publish if we've never advertised, dropped, OR the device's
                    // LAN IP changed under us (DHCP) — a stale-IP record is as bad as
                    // none for MA's server-initiated connect.
                    if (ad.isActive() && !ad.addressChanged()) {
                        kotlinx.coroutines.delay(ADVERTISE_HEALTH_INTERVAL_MS)
                        continue
                    }
                    val ok = ad.register(serviceName = clientId, port = newHost.port, properties = advertiseProps)
                    kotlinx.coroutines.delay(if (ok) ADVERTISE_HEALTH_INTERVAL_MS else ADVERTISE_RETRY_DELAY_MS)
                }
            }
        }

        // Unified topology loop (official MA app style). The outbound dial URL
        // is re-resolved from prefs EVERY tick: MA config commonly lands after
        // bootstrap (companion sync writes per-user scoped keys, onboarding,
        // user switch), and a one-shot capture left those installs stuck on the
        // legacy advertise topology — 15 s wrapper waits and minutes-long blip
        // recovery — even though a dial target was sitting in prefs. While a
        // URL is known we dial out and deliberately do NOT advertise via mDNS
        // (the server would also dial in and the two sessions would fight);
        // the advertise topology engages immediately when no URL is stored, or
        // as a fallback after a few failed dials to a stale/unreachable URL.
        cs.launch {
            var disconnectedTicks = 0
            var advertiseStarted = false
            fun ensureAdvertising(reason: String) {
                if (advertiseStarted || !hostUsable) return
                Timber.tag(TAG).i("Enabling mDNS advertise topology: %s", reason)
                advertiseStarted = true
                launchAdvertiseLoop()
            }
            while (true) {
                val st = newClient.state.value
                val connected = newClient.serverId.value.isNotBlank() &&
                    st != ClientState.DISCONNECTED && st != ClientState.ERROR
                if (connected) {
                    disconnectedTicks = 0
                } else if (st != ClientState.CONNECTING && st != ClientState.HANDSHAKING) {
                    val dialUrl = currentOutboundDialUrl()
                    val remoteId = currentMusicAssistantRemoteId()
                    val onLan = isOnLocalNetwork()
                    // Network-aware topology selection (the LAN /sendspin outbound dial can't
                    // be used as a reachability probe — MA 2.9+ rejects it with `4001 first
                    // message must be auth`, so it "fails" even at home):
                    //   • Off Wi-Fi/Ethernet (cellular only) + a Remote ID  → WebRTC tunnel.
                    //   • On Wi-Fi/Ethernet → LAN paths (outbound dial, then mDNS advertise so
                    //     MA connects in), exactly as before — never relay audio when home.
                    val useWebRtc = remoteId != null && !onLan
                    if (useWebRtc) {
                        ensureWebRtcRemote(remoteId)
                    } else {
                        stopWebRtcRemote("on local network")
                        if (dialUrl != null) {
                            runCatching { newClient.connect(dialUrl) }
                                .onFailure {
                                    Timber.tag(TAG).d(it, "Outbound SendSpin dial to %s failed", dialUrl)
                                }
                            disconnectedTicks++
                            if (disconnectedTicks >= OUTBOUND_DIALS_BEFORE_ADVERTISE_FALLBACK) {
                                ensureAdvertising("outbound dial keeps failing")
                            }
                        } else {
                            ensureAdvertising("no Music Assistant URL stored")
                        }
                    }
                }
                kotlinx.coroutines.delay(OUTBOUND_DIAL_CHECK_INTERVAL_MS)
            }
        }

        ensureStallWatchdog()
    }

    /**
     * The ws(s) URL the topology loop should dial, or null when no MA server is
     * known. Tries the current user scope first, then ANY user's stored URL:
     * the service usually bootstraps before an MA intent has delivered the
     * active Jellyfin user id, so a scoped-only read missed every per-user
     * config (`u:<userId>/…`) and silently degraded to the advertise topology.
     */
    private fun currentOutboundDialUrl(): String? =
        (storedMusicAssistantServerUrl(null) ?: anyStoredMusicAssistantUrl())?.toSendspinWsUrl()

    /** The Music Assistant Remote ID to reach off-LAN, or null when none is configured. */
    private fun currentMusicAssistantRemoteId(): RemoteId? =
        RemoteId.parse(storedMusicAssistantRemoteId() ?: anyStoredMusicAssistantRemoteId())

    /**
     * Bring up (or keep) the WebRTC remote tunnel to [remoteId]. Idempotent: a no-op when a
     * connection for the same Remote ID is already live. Once the `sendspin` data channel
     * opens, the stock [SendSpinClient] is dialed at the loopback relay so the rest of the
     * receiver pipeline is identical to a LAN connection. Called from the topology loop, so
     * a dropped connection (onClosed clears the field) is retried on the next tick.
     */
    private fun ensureWebRtcRemote(remoteId: RemoteId) {
        if (webRtcConnection != null && webRtcRemoteId == remoteId.rawId) return
        stopWebRtcRemote("remote id changed")
        val http = okHttpClient ?: return
        webRtcRemoteId = remoteId.rawId
        val conn = MaWebRtcConnection(
            appContext = applicationContext,
            httpClient = http,
            remoteId = remoteId,
            deviceName = displayName,
            tokenProvider = { currentMusicAssistantToken() },
            onSendspinReady = { loopbackUrl ->
                val c = client
                if (c == null) {
                    Timber.tag(TAG).w("WebRTC sendspin ready but SendSpinClient is null; cannot dial loopback")
                } else {
                    Timber.tag(TAG).i("WebRTC sendspin ready; dialing SendSpinClient at %s", loopbackUrl)
                    runCatching { c.connect(loopbackUrl) }
                        .onFailure { Timber.tag(TAG).w(it, "SendSpin loopback dial failed") }
                }
            },
            onMaApiReady = { transport, httpProxy ->
                maApiTransport = transport
                maApiHttpProxy = httpProxy
                applyMaApiTransport()
                // Surface the remote players once control is reachable.
                if (transport != null) refreshMusicAssistantPlayers(discoverServerUrl = false)
            },
            onClosed = { reason ->
                Timber.tag(TAG).i("WebRTC remote closed (%s); topology loop will retry", reason)
                webRtcConnection = null
                webRtcRemoteId = null
                maApiTransport = null
                maApiHttpProxy = null
                applyMaApiTransport()
            },
        )
        webRtcConnection = conn
        Timber.tag(TAG).i("Starting WebRTC remote to Music Assistant (remoteId=%s)", remoteId.rawId)
        conn.start()
    }

    private fun stopWebRtcRemote(reason: String) {
        val conn = webRtcConnection ?: return
        webRtcConnection = null
        webRtcRemoteId = null
        maApiTransport = null
        maApiHttpProxy = null
        applyMaApiTransport()
        runCatching { conn.close(reason) }
    }

    /** Point the MA control client at the WebRTC ma-api transport (or back to REST when null). */
    private fun applyMaApiTransport() {
        val transport = maApiTransport
        val httpProxy = maApiHttpProxy
        if (transport != null) {
            // Make sure a client exists to carry the transport (the WebRTC path can become
            // ready before any MA intent has lazily built one).
            ensureMusicAssistantClient().rpcTransport = transport
            // Expose the transport to other modules (e.g. the :modes:film home rows) so their
            // MA API calls also work off-LAN.
            MusicAssistantRemoteBridge.remoteExecutor = { command, argsJson ->
                transport.execute(command, argsJson?.let { org.json.JSONObject(it) })
            }
            MusicAssistantRemoteBridge.remoteImageFetcher = { path ->
                httpProxy?.get(path = path) ?: throw IllegalStateException("HTTP Proxy not available")
            }
        } else {
            musicAssistantClient?.rpcTransport = null
            MusicAssistantRemoteBridge.remoteExecutor = null
            MusicAssistantRemoteBridge.remoteImageFetcher = null
        }
        SendspinReceiverSession.update { it.copy(musicAssistantRemoteReady = transport != null) }
    }

    /** True when MA control is reachable only over the WebRTC ma-api channel (no LAN URL). */
    private fun maRemoteControlActive(): Boolean = maApiTransport != null

    /**
     * True when the device is on Wi-Fi/Ethernet (LAN paths viable), false on cellular-only or
     * no network. Used to decide LAN vs WebRTC remote: the LAN /sendspin dial can't probe
     * reachability because MA 2.9+ rejects it (`4001`) even at home. Defaults to true (LAN)
     * if connectivity can't be read, so we never spuriously relay when home.
     */
    private fun isOnLocalNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Best available stored MA token for [currentJellyfinUserId] (any URL/server), used to
     * authenticate the WebRTC ma-api channel. The token from a prior LAN login/paste is reused.
     */
    private fun currentMusicAssistantToken(): String? {
        val all = runCatching { musicAssistantPrefs().all }.getOrNull() ?: return null
        val scope = currentJellyfinUserId?.takeIf { it.isNotBlank() } ?: "default"
        fun pick(predicate: (String) -> Boolean): String? =
            all.entries.asSequence()
                .filter { (key, value) -> predicate(key) && value is String && value.isNotBlank() }
                .map { it.value as String }
                .firstOrNull()
        // Prefer the active user's token, then any stored token, then a legacy global one.
        return pick { it.startsWith("u:$scope/") && it.contains("/$PREF_MA_TOKEN_URL_PREFIX") }
            ?: pick { it.startsWith("u:$scope/") && it.contains("/$PREF_MA_TOKEN_SERVER_PREFIX") }
            ?: pick { it.contains("/$PREF_MA_TOKEN_URL_PREFIX") || it.contains("/$PREF_MA_TOKEN_SERVER_PREFIX") }
            ?: pick { it.startsWith(PREF_MA_TOKEN_URL_PREFIX) || it.startsWith(PREF_MA_TOKEN_SERVER_PREFIX) }
    }

    /**
     * Guard against SendSpin clock discontinuities that map fresh audio chunks
     * outside [AudioBuffer]'s playable window. sendspin-jvm does not expose a
     * supported way to reset its [ClockSync], so rebuild the client to obtain a
     * fresh filter instead of mutating the filter's private Kalman state. The
     * filter also outlives socket reconnects, so [recoveryGuard] first requires
     * a measurement newer than the active connection's hello-time baseline.
     */
    private fun recoverSendspinClockIfNeeded(
        client: SendSpinClient,
        chunk: AudioChunk,
        recoveryGuard: SendspinClockRecoveryGuard,
    ) {
        if (this.client !== client) return
        val connectionToken = client.serverHello.value ?: return
        val readingBeforeMapping = client.clockSync.recoveryReading()
        if (!recoveryGuard.isClockReady(connectionToken, readingBeforeMapping)) return

        val nowUs = ClockSync.Companion.localMicros()
        val mappedUs = client.toLocalMicros(chunk.serverTimestampMicros)
        val readingAfterMapping = client.clockSync.recoveryReading()
        // Do not classify a chunk across a concurrent ClockSync update: its mapping may have
        // used a different filter state than the reading that armed this generation.
        if (readingAfterMapping != readingBeforeMapping) return
        val skewUs = mappedUs - nowUs
        val needsRecovery =
            skewUs < -CLOCK_RECOVERY_LATE_THRESHOLD_US ||
                skewUs > CLOCK_RECOVERY_FUTURE_THRESHOLD_US
        if (!recoveryGuard.recordChunk(connectionToken, needsRecovery)) return

        if (!scheduleClientRebuild("clock_discontinuity")) return
        client.audioBuffer.flush()
        if (abs(nowUs - lastClockRecoveryLogUs) >= CLOCK_RECOVERY_LOG_INTERVAL_US) {
            lastClockRecoveryLogUs = nowUs
            Timber.tag(TAG).w(
                "Sendspin clock discontinuity: chunkTs=%d skew=%.1f ms; rebuilding client",
                chunk.serverTimestampMicros,
                skewUs / 1_000.0,
            )
        }
    }

    private fun anyStoredMusicAssistantUrl(): String? {
        val all = runCatching { musicAssistantPrefs().all }.getOrNull() ?: return null
        return all.entries.asSequence()
            .filter { (key, _) ->
                key == PREF_MA_LAST_URL ||
                    (key.startsWith("u:") && key.endsWith("/$PREF_MA_LAST_URL"))
            }
            .mapNotNull { (_, value) -> (value as? String)?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?.let { normalizeMusicAssistantUrl(it) }
    }

    /** Current user's stored Remote ID (scoped, falling back to a legacy global key). */
    private fun storedMusicAssistantRemoteId(): String? {
        val prefs = musicAssistantPrefs()
        prefs.getString(scopedKey(PREF_MA_REMOTE_ID), null)?.takeIf { it.isNotBlank() }?.let { return it }
        return prefs.getString(PREF_MA_REMOTE_ID, null)?.takeIf { it.isNotBlank() } // legacy/global
    }

    /** Any user's stored Remote ID — the service often bootstraps before the user id lands. */
    private fun anyStoredMusicAssistantRemoteId(): String? {
        val all = runCatching { musicAssistantPrefs().all }.getOrNull() ?: return null
        return all.entries.asSequence()
            .filter { (key, _) ->
                key == PREF_MA_REMOTE_ID ||
                    (key.startsWith("u:") && key.endsWith("/$PREF_MA_REMOTE_ID"))
            }
            .mapNotNull { (_, value) -> (value as? String)?.takeIf { it.isNotBlank() } }
            .firstOrNull()
    }

    private fun storeMusicAssistantRemoteId(remoteId: String) {
        musicAssistantPrefs().edit()
            .putString(scopedKey(PREF_MA_REMOTE_ID), remoteId)
            .apply()
    }

    private fun setMusicAssistantRemoteId(rawRemoteId: String) {
        val parsed = RemoteId.parse(rawRemoteId)
        if (parsed == null) {
            Timber.tag(TAG).w("Ignoring invalid Music Assistant Remote ID")
            SendspinReceiverSession.update {
                it.copy(musicAssistantError = "Invalid Remote ID — expected 26 characters from Music Assistant → Remote Access.")
            }
            return
        }
        storeMusicAssistantRemoteId(parsed.rawId)
        SendspinReceiverSession.update {
            it.copy(musicAssistantRemoteId = parsed.rawId, musicAssistantError = null)
        }
        // A new Remote ID may change the reachable transport — drop any stale tunnel so the
        // topology loop re-evaluates (LAN vs WebRTC) on its next tick.
        stopWebRtcRemote("remote id updated")
    }

    /**
     * Backstop for "MA says PLAYING but this device renders nothing". The
     * WebSocket ping catches dead sockets, but a live socket can still go
     * silent: a clock discontinuity after device suspend makes every chunk
     * look far-future (the library's Kalman filter has no discontinuity reset
     * and takes minutes to re-converge), or the server-side stream stalls.
     * Watching the renderer's monotonic byte counter catches all of those.
     * After [STALL_TICKS_MARK_UI] silent ticks the session is flagged so the
     * UI stops claiming playback; after [STALL_TICKS_REBUILD] the entire
     * client stack is rebuilt — fresh socket, fresh clock-sync filter — and
     * the server late-joins us back into the running stream.
     */
    private fun ensureStallWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            var lastRendered = -1L
            var stalledTicks = 0
            while (true) {
                kotlinx.coroutines.delay(STALL_CHECK_INTERVAL_MS)
                val session = SendspinReceiverSession.state.value
                val playing = session.connected &&
                    session.playbackState == SendspinReceiverPlaybackState.PLAYING
                val rendered = audioPlayer?.totalBytesRendered ?: -1L
                if (rendered < 0L) {
                    // Rebuild in progress (no audio player yet) — keep the
                    // stalled flag up; audio genuinely isn't flowing.
                    lastRendered = -1L
                    stalledTicks = 0
                    continue
                }
                if (!playing || rendered != lastRendered) {
                    lastRendered = rendered
                    stalledTicks = 0
                    if (session.audioStalled) {
                        SendspinReceiverSession.update { it.copy(audioStalled = false) }
                        updateNotification()
                    }
                    continue
                }
                stalledTicks++
                if (stalledTicks == STALL_TICKS_MARK_UI) {
                    Timber.tag(TAG).w(
                        "Sendspin audio stalled while PLAYING (%d ms without rendered PCM)",
                        stalledTicks * STALL_CHECK_INTERVAL_MS,
                    )
                    SendspinReceiverSession.update { it.copy(audioStalled = true) }
                    updateNotification()
                }
                if (stalledTicks >= STALL_TICKS_REBUILD) {
                    Timber.tag(TAG).w(
                        "Sendspin audio stalled for %d ms — rebuilding client (fresh socket + clock sync)",
                        stalledTicks * STALL_CHECK_INTERVAL_MS,
                    )
                    stalledTicks = 0
                    lastRendered = -1L
                    scheduleClientRebuild("stall_recovery")
                }
            }
        }
    }

    /** Schedules at most one client rebuild across the watchdog and clock-discontinuity paths. */
    private fun scheduleClientRebuild(reason: String): Boolean =
        synchronized(clientLifecycleLock) {
            if (bootstrapJob?.isActive == true) return@synchronized false
            bootstrapJob =
                scope.launch {
                    teardownClient(reason)
                    startServer(displayName, receiverClientId, lastPort, lastSoftwareVersion)
                }
            true
        }

    /**
     * Tear down everything [startServer] built (socket, host, audio, per-client
     * collectors, MA REST client) so it can be rebuilt or the service stopped.
     * Returns the async advertiser-unregister job so [onDestroy] can sequence
     * scope cancellation after it.
     */
    private fun teardownClient(reason: String): Job {
        stopWebRtcRemote("teardown: $reason")
        runCatching { client?.disconnect(reason) }
        runCatching { serverHost?.stopServer() }
        audioPlayer?.close()
        okHttpClient?.dispatcher?.executorService?.shutdown()
        okHttpClient?.connectionPool?.evictAll()
        clientScope?.cancel()
        clientScope = null
        client = null
        serverHost = null
        audioPlayer = null
        okHttpClient = null
        // The MA REST client shares the torn-down OkHttp dispatcher; drop it so
        // the next ensureMusicAssistantClient() builds a working one.
        musicAssistantClient = null
        val ad = advertiser
        advertiser = null
        return scope.launch { ad?.unregister() }
    }

    /** `http(s)://host:port` MA base URL → `ws(s)://host:port/sendspin`. */
    private fun String.toSendspinWsUrl(): String =
        replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/sendspin"

    override fun onDestroy() {
        super.onDestroy()
        bootstrapJob?.cancel()
        watchdogJob?.cancel()
        watchdogJob = null
        val unregisterJob = teardownClient("service_stop")
        musicAssistantRefreshJob?.cancel()
        musicAssistantRefreshJob = null
        SendspinReceiverSession.reset()
        releaseWifiLock()
        unregisterJob.invokeOnCompletion { scope.cancel() }
    }

    private fun handleControllerCommand(intent: Intent) {
        if (intent.action == ACTION_DISMISS_CONTROLS) {
            SendspinReceiverSession.dismissControls()
            updateNotification()
            return
        }
        when (intent.action) {
            ACTION_MUSIC_ASSISTANT_REFRESH -> {
                refreshMusicAssistantPlayers(discoverServerUrl = true)
                return
            }
            ACTION_MUSIC_ASSISTANT_LOGIN -> {
                val username = intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_USERNAME).orEmpty()
                val password = intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_PASSWORD).orEmpty()
                val serverUrl =
                    intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_SERVER_URL)
                        ?.normalizeMusicAssistantUrl()
                        ?: SendspinReceiverSession.state.value.musicAssistantServerUrl
                loginMusicAssistant(serverUrl, username, password)
                return
            }
            ACTION_MUSIC_ASSISTANT_SAVE_TOKEN -> {
                val token = intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_TOKEN).orEmpty()
                val serverUrl =
                    intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_SERVER_URL)
                        ?.normalizeMusicAssistantUrl()
                        ?: SendspinReceiverSession.state.value.musicAssistantServerUrl
                saveMusicAssistantToken(serverUrl, token)
                return
            }
            ACTION_MUSIC_ASSISTANT_SET_SERVER_URL -> {
                val serverUrl =
                    intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_SERVER_URL)
                        ?.normalizeMusicAssistantUrl()
                setMusicAssistantServerUrl(serverUrl)
                return
            }
            ACTION_MUSIC_ASSISTANT_SET_REMOTE_ID -> {
                setMusicAssistantRemoteId(
                    intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_REMOTE_ID).orEmpty(),
                )
                return
            }
            ACTION_MUSIC_ASSISTANT_SET_MEMBER -> {
                val playerId = intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_PLAYER_ID).orEmpty()
                val add = intent.getBooleanExtra(EXTRA_MUSIC_ASSISTANT_MEMBER_ADD, false)
                setMusicAssistantGroupMember(playerId, add)
                return
            }
            ACTION_MUSIC_ASSISTANT_PLAY_MEDIA -> {
                val mediaUri = intent.getStringExtra(EXTRA_MUSIC_ASSISTANT_MEDIA_URI).orEmpty()
                playMusicAssistantMediaAction(mediaUri)
                return
            }
            ACTION_MUSIC_ASSISTANT_SET_USER -> {
                // currentJellyfinUserId was already updated in onStartCommand;
                // reload the session for the new user's stored URL/token so the
                // controls UI reflects the switched user (or shows unconfigured).
                SendspinReceiverSession.update {
                    it.copy(musicAssistantRemoteId = storedMusicAssistantRemoteId())
                }
                // The reachable transport may differ for the new user; drop any tunnel
                // scoped to the previous user so the topology loop re-resolves.
                stopWebRtcRemote("user switched")
                refreshMusicAssistantPlayers(discoverServerUrl = true)
                return
            }
        }

        val command = intent.getStringExtra(EXTRA_CONTROLLER_COMMAND) ?: return
        val volume = if (intent.hasExtra(EXTRA_CONTROLLER_VOLUME)) {
            intent.getIntExtra(EXTRA_CONTROLLER_VOLUME, 0).coerceIn(0, 100)
        } else {
            null
        }
        val muted = if (intent.hasExtra(EXTRA_CONTROLLER_MUTED)) {
            intent.getBooleanExtra(EXTRA_CONTROLLER_MUTED, false)
        } else {
            null
        }
        val currentClient = client
        if (currentClient == null) {
            Timber.tag(TAG).w("ignoring Sendspin command without active client: %s", command)
            return
        }

        if (command == SendspinControllerCommands.STOP) {
            smoothedVisualizerLevels = emptyList()
        }
        currentClient.sendControllerCommand(command, volume, muted)
        SendspinReceiverSession.update { it.applyOptimisticCommand(command, volume, muted) }
        updateNotification()
    }

    private fun refreshMusicAssistantPlayers(discoverServerUrl: Boolean = false) {
        musicAssistantRefreshJob?.cancel()
        musicAssistantRefreshJob =
            scope.launch {
                SendspinReceiverSession.update {
                    it.copy(musicAssistantLoading = true, musicAssistantError = null)
                }
                val currentState = SendspinReceiverSession.state.value
                val serverId = currentState.serverId
                if (maRemoteControlActive()) {
                    // Off-LAN: control routes over the ma-api channel; REST URL/token and the
                    // HTTP discovery step don't apply (and would just time out off-LAN).
                    runCatching {
                        val players = requireNotNull(musicAssistantClient).fetchPlayers("", "")
                        updateMusicAssistantPlayers(currentState.musicAssistantServerUrl, null, players)
                    }.onFailure { error ->
                        handleMusicAssistantError(currentState.musicAssistantServerUrl, error)
                    }
                    return@launch
                }
                val serverUrl =
                    when {
                        discoverServerUrl -> discoverMusicAssistantServerUrl(serverId)
                        else -> currentState.musicAssistantServerUrl
                    }
                if (serverUrl.isNullOrBlank()) {
                    SendspinReceiverSession.update {
                        it.copy(
                            musicAssistantLoading = false,
                            musicAssistantAuthState = SendspinMusicAssistantAuthState.MISSING,
                            musicAssistantError = "Music Assistant server not found",
                        )
                    }
                    return@launch
                }

                val token = musicAssistantToken(serverId, serverUrl)
                if (token.isNullOrBlank()) {
                    SendspinReceiverSession.update {
                        it.copy(
                            musicAssistantServerUrl = serverUrl,
                            musicAssistantLoading = false,
                            musicAssistantAuthState = SendspinMusicAssistantAuthState.MISSING,
                            musicAssistantError = null,
                            musicAssistantPlayers = emptyList(),
                        )
                    }
                    return@launch
                }

                runCatching {
                    val players =
                        requireNotNull(musicAssistantClient).fetchPlayers(serverUrl, token)
                    updateMusicAssistantPlayers(serverUrl, token, players)
                }.onFailure { error ->
                    handleMusicAssistantError(serverUrl, error)
                }
            }
    }

    private fun loginMusicAssistant(serverUrl: String?, username: String, password: String) {
        if (serverUrl.isNullOrBlank() || username.isBlank() || password.isBlank()) {
            SendspinReceiverSession.update {
                it.copy(
                    musicAssistantAuthState = SendspinMusicAssistantAuthState.MISSING,
                    musicAssistantError = "Music Assistant login needs server, username, and password",
                )
            }
            return
        }
        musicAssistantRefreshJob?.cancel()
        musicAssistantRefreshJob =
            scope.launch {
                SendspinReceiverSession.update {
                    it.copy(
                        musicAssistantServerUrl = serverUrl,
                        musicAssistantLoading = true,
                        musicAssistantAuthState = SendspinMusicAssistantAuthState.AUTHENTICATING,
                        musicAssistantError = null,
                    )
                }
                runCatching {
                    val token =
                        requireNotNull(musicAssistantClient).login(serverUrl, username, password)
                    storeMusicAssistantToken(SendspinReceiverSession.state.value.serverId, serverUrl, token)
                    val players =
                        requireNotNull(musicAssistantClient).fetchPlayers(serverUrl, token)
                    updateMusicAssistantPlayers(serverUrl, token, players)
                }.onFailure { error ->
                    handleMusicAssistantError(serverUrl, error)
                }
            }
    }

    private fun saveMusicAssistantToken(serverUrl: String?, token: String) {
        if (serverUrl.isNullOrBlank() || token.isBlank()) {
            SendspinReceiverSession.update {
                it.copy(
                    musicAssistantAuthState = SendspinMusicAssistantAuthState.MISSING,
                    musicAssistantError = "Music Assistant token and server URL are required",
                )
            }
            return
        }
        storeMusicAssistantToken(SendspinReceiverSession.state.value.serverId, serverUrl, token.trim())
        refreshMusicAssistantPlayers(discoverServerUrl = false)
    }

    private fun setMusicAssistantServerUrl(serverUrl: String?) {
        if (serverUrl.isNullOrBlank()) {
            SendspinReceiverSession.update {
                it.copy(
                    musicAssistantAuthState = SendspinMusicAssistantAuthState.MISSING,
                    musicAssistantError = "Music Assistant server URL is required",
                )
            }
            return
        }
        storeMusicAssistantServerUrl(SendspinReceiverSession.state.value.serverId, serverUrl)
        SendspinReceiverSession.update {
            it.copy(musicAssistantServerUrl = serverUrl, musicAssistantError = null)
        }
        refreshMusicAssistantPlayers(discoverServerUrl = false)
        // A (re)configured MA server is a dial target: connect out immediately
        // instead of waiting for the server to discover our mDNS advert.
        client?.let { c ->
            if (SendspinReceiverSession.state.value.serverId.isNullOrBlank()) {
                scope.launch {
                    runCatching { c.connect(serverUrl.toSendspinWsUrl()) }
                        .onFailure { Timber.tag(TAG).d(it, "Outbound SendSpin dial after URL change failed") }
                }
            }
        }
    }

    private fun setMusicAssistantGroupMember(playerId: String, add: Boolean) {
        if (playerId.isBlank()) return
        val state = SendspinReceiverSession.state.value
        val remote = maRemoteControlActive()
        val serverUrl = state.musicAssistantServerUrl
        val targetPlayer = state.musicAssistantGroupTargetPlayerId
        val token = musicAssistantToken(state.serverId, serverUrl)
        // Over ma-api the URL/token aren't needed (the transport ignores them); on LAN both are.
        val ready = !targetPlayer.isNullOrBlank() &&
            (remote || (!serverUrl.isNullOrBlank() && !token.isNullOrBlank()))
        if (!ready) {
            SendspinReceiverSession.update {
                it.copy(
                    musicAssistantAuthState =
                        if (!remote && token.isNullOrBlank()) {
                            SendspinMusicAssistantAuthState.MISSING
                        } else {
                            it.musicAssistantAuthState
                        },
                    musicAssistantError = "Music Assistant group controls are not ready",
                )
            }
            return
        }
        val callUrl = serverUrl.orEmpty()
        val callToken = token.orEmpty()
        scope.launch {
            SendspinReceiverSession.update {
                it.copy(
                    musicAssistantBusyPlayerIds = it.musicAssistantBusyPlayerIds + playerId,
                    musicAssistantError = null,
                )
            }
            runCatching {
                requireNotNull(musicAssistantClient).setMembers(
                    baseUrl = callUrl,
                    token = callToken,
                    targetPlayer = targetPlayer,
                    playerIdsToAdd = if (add) listOf(playerId) else emptyList(),
                    playerIdsToRemove = if (add) emptyList() else listOf(playerId),
                )
                val players = requireNotNull(musicAssistantClient).fetchPlayers(callUrl, callToken)
                updateMusicAssistantPlayers(if (remote) null else serverUrl, if (remote) null else token, players)
            }.onFailure { error ->
                handleMusicAssistantError(serverUrl, error)
            }
            SendspinReceiverSession.update {
                it.copy(musicAssistantBusyPlayerIds = it.musicAssistantBusyPlayerIds - playerId)
            }
        }
    }

    private fun playMusicAssistantMediaAction(mediaUri: String) {
        if (mediaUri.isBlank()) return
        scope.launch {
            try {
                val state = SendspinReceiverSession.state.value
                val serverId = state.serverId
                val remote = maRemoteControlActive()
                val serverUrl =
                    if (remote) state.musicAssistantServerUrl
                    else state.musicAssistantServerUrl ?: discoverMusicAssistantServerUrl(serverId)
                val token = if (remote) currentMusicAssistantToken() else musicAssistantToken(serverId, serverUrl)
                if (!remote && (serverUrl.isNullOrBlank() || token.isNullOrBlank())) {
                    Timber.tag(TAG).w("MA play_media: no server URL or token (serverId=%s)", serverId)
                    showToast("Music Assistant: connect a server in settings first")
                    return@launch
                }
                val callUrl = serverUrl.orEmpty()
                val callToken = token.orEmpty()

                val client = requireNotNull(musicAssistantClient)
                val target = resolvePlayMediaTarget(client, callUrl, callToken)
                if (target == null) {
                    Timber.tag(TAG).w("MA play_media: no usable target")
                    // Tell the user *why* we refused to play. The most common
                    // case after a fresh receiver registration is that MA's
                    // 15 s auto-wrap timer hasn't fired yet.
                    showToast(
                        "Music Assistant is still linking this device. " +
                            "Wait ~15 s after the SendSpin receiver appears, then retry.",
                    )
                    return@launch
                }
                SendspinReceiverSession.update {
                    it.copy(
                        musicAssistantServerUrl = serverUrl ?: it.musicAssistantServerUrl,
                        musicAssistantToken = token ?: it.musicAssistantToken,
                        musicAssistantAuthState = SendspinMusicAssistantAuthState.AUTHENTICATED,
                        musicAssistantError = null,
                        musicAssistantQueuePlayerId = target.id,
                    )
                }
                // Fresh playback always resolves the current device's MA queue
                // target. Other speakers join through MA sync grouping after
                // playback starts; a saved picker target from an older build
                // must never make a new tap start silently in another room.
                Timber.tag(TAG).i(
                    "MA play_media: uri=%s queue_id=%s (player_name=%s type=%s provider=%s)",
                    mediaUri, target.id, target.name, target.type, target.provider,
                )

                client.playMedia(
                    baseUrl = callUrl,
                    token = callToken,
                    queueId = target.id,
                    mediaUri = mediaUri,
                )
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "MA play_media failed for uri=%s", mediaUri)
                val message = (error as? MusicAssistantApiException)?.userMessage()
                    ?: error.message?.takeIf { it.isNotBlank() }
                    ?: "Unknown error"
                showToast("Music Assistant: $message")
            }
        }
    }

    /**
     * Pick the player to send `play_media` to.
     *
     * **Hard rule:** when this device has a SendSpin endpoint registered with
     * MA, we ALWAYS target this device's queue/player id — never a sibling
     * wrapper for someone else's device. A previous version of this resolver
     * fell back to "any usable universal_player" if our wrapper wasn't ready
     * yet, which silently routed playback to whichever room MA happened to list
     * first. Bad UX, and on a multi-room MA install (20+ players) it's a privacy
     * bug too.
     *
     * Order, with no implicit cross-device fallback:
     *   1) The same-name MA player when MA already exposes it as a usable queue
     *      target. This covers builds where the name lookup returns the
     *      Universal-Player itself instead of the raw protocol endpoint.
     *   2) Our SendSpin endpoint's Universal-Player wrapper, found via
     *      `active_source` / `protocol_parent_id` / `linked_output_protocols`.
     *      Polls briefly so MA's 15 s auto-link timer can catch up — but
     *      ONLY if we have a SendSpin endpoint to wrap.
     *   3) If our SendSpin endpoint exists but the wrapper never appears:
     *      try the endpoint itself as a last resort. Do NOT pick a different
     *      player.
     */
    private suspend fun resolvePlayMediaTarget(
        client: MusicAssistantGroupClient,
        serverUrl: String,
        token: String,
    ): MusicAssistantPlayerState? {
        val start = System.currentTimeMillis()
        val deadline = start + WRAPPER_POLL_TIMEOUT_MS
        var attempt = 0

        while (true) {
            val all = client.fetchPlayers(serverUrl, token)
            val usable = all.filter { it.isUsableMusicAssistantQueueTarget() }

            // Find this device's MA player. Exact id is the raw advertised
            // SendSpin client id. Name fallback is needed because MA may expose
            // the same device as a generated Universal-Player id (`up...`).
            val ourSendspin = thisDeviceMusicAssistantPlayer(all)
            if (ourSendspin != null) {
                if (ourSendspin.isUsableMusicAssistantQueueTarget()) {
                    Timber.tag(TAG).d(
                        "MA play_media: using this device's MA queue target player_id=%s (type=%s provider=%s attempt=%d)",
                        ourSendspin.id, ourSendspin.type, ourSendspin.provider, attempt,
                    )
                    return ourSendspin
                }

                val wrapperId = thisDeviceWrapperId(ourSendspin, all)
                if (wrapperId != null) {
                    val wrapper = usable.firstOrNull { it.id == wrapperId }
                    if (wrapper != null) {
                        Timber.tag(TAG).d(
                            "MA play_media: using auto-detected wrapper for this device player_id=%s (attempt=%d)",
                            wrapperId, attempt,
                        )
                        return wrapper
                    }
                }
                Timber.tag(TAG).d(
                    "MA play_media: SendSpin endpoint registered (%s type=%s provider=%s) but wrapper not linked yet (attempt=%d, elapsed=%dms)",
                    ourSendspin.id, ourSendspin.type, ourSendspin.provider, attempt, System.currentTimeMillis() - start,
                )
            } else {
                // MA hasn't registered THIS device's SendSpin endpoint yet.
                // This code only runs inside the receiver service, so this
                // device IS a SendSpin receiver — the user expects audio HERE.
                // Never fall through to "any usable player" (that's how a play
                // silently landed on a random speaker). Poll for our endpoint
                // to appear, then refuse rather than route elsewhere.
                Timber.tag(TAG).d(
                    "MA play_media: this device's SendSpin endpoint not visible in MA yet " +
                        "(attempt=%d, elapsed=%dms) — polling",
                    attempt, System.currentTimeMillis() - start,
                )
            }

            // Neither our wrapper nor our endpoint resolved this pass. Poll
            // until the deadline, then try this device's endpoint directly.
            if (System.currentTimeMillis() < deadline) {
                val nextDelay = if (attempt < FAST_POLLS) FAST_POLL_INTERVAL_MS else SLOW_POLL_INTERVAL_MS
                kotlinx.coroutines.delay(nextDelay)
                attempt++
                continue
            }
            ourSendspin
                ?.takeIf { it.available && it.enabled }
                ?.let {
                    Timber.tag(TAG).w(
                        "MA play_media: this device has no usable wrapper after %dms — trying endpoint directly player_id=%s",
                        WRAPPER_POLL_TIMEOUT_MS, it.id,
                    )
                    return it
                }
            Timber.tag(TAG).w(
                "MA play_media: this device has no usable endpoint after %dms — refusing to route to another player",
                WRAPPER_POLL_TIMEOUT_MS,
            )
            return null
        }
    }

    private fun MusicAssistantPlayerState.isUsableMusicAssistantQueueTarget(): Boolean =
        !type.equals("protocol", ignoreCase = true) && available && enabled

    private fun thisDeviceMusicAssistantPlayer(
        players: List<MusicAssistantPlayerState>,
    ): MusicAssistantPlayerState? =
        players.firstOrNull { it.id == receiverClientId }
            ?: players.firstOrNull {
                it.name.equals(displayName, ignoreCase = true) &&
                    it.isUsableMusicAssistantQueueTarget()
            }
            ?: players.firstOrNull { it.name.equals(displayName, ignoreCase = true) }

    /**
     * Reverse lookup: given our protocol player, find the id of the
     * Universal-Player wrapper that MA has linked to it (if any).
     *
     * - `active_source` is set by MA the moment the wrapper takes over
     *   playback routing, and is what the official app's PlayerFactory uses
     *   ([PlayerFactory.create], line 36 of the mobile app).
     * - `protocol_parent_id` is the convenience reverse pointer some MA
     *   versions set at link time.
     * - As a last resort, scan every player and check whether one of them
     *   lists us in `linked_output_protocols`. That's the authoritative
     *   forward pointer MA's `UniversalPlayerProvider` writes regardless of
     *   version.
     */
    private fun thisDeviceWrapperId(
        protocolPlayer: MusicAssistantPlayerState,
        allPlayers: List<MusicAssistantPlayerState>,
    ): String? {
        protocolPlayer.activeSource?.takeIf { it.isNotBlank() && it != protocolPlayer.id }
            ?.let { return it }
        protocolPlayer.protocolParentId?.takeIf { it.isNotBlank() }?.let { return it }
        return allPlayers.firstOrNull { candidate ->
            candidate.provider.equals("universal_player", ignoreCase = true) &&
                protocolPlayer.id in candidate.linkedOutputProtocols
        }?.id
    }

    private fun showToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this@SendspinReceiverService,
                message,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun MusicAssistantApiException.userMessage(): String {
        // PlayerUnavailableError is the most common server-side rejection for
        // play_media. Surface its real message instead of "API request failed".
        val match = Regex("\"([^\"]+Error)\"\\s*,\\s*\"([^\"]+)\"")
            .find(responseBody)
        if (match != null) {
            val kind = match.groupValues[1]
            val detail = match.groupValues[2]
            return "$kind: $detail"
        }
        return "HTTP $statusCode"
    }

    /**
     * Legacy helper retained for the grouping target derivation only.
     * The play_media path uses [resolvePlayMediaTarget] / [thisDeviceWrapperId].
     */
    @Suppress("UNUSED")
    private fun resolveQueueId(
        player: MusicAssistantPlayerState,
        allPlayers: List<MusicAssistantPlayerState>,
    ): String? {
        if (!player.type.equals("protocol", ignoreCase = true)) return player.id
        player.protocolParentId?.takeIf { it.isNotBlank() }?.let { return it }
        val wrapper = allPlayers.firstOrNull { candidate ->
            candidate.provider.equals("universal_player", ignoreCase = true) &&
                player.id in candidate.linkedOutputProtocols
        }
        return wrapper?.id
    }

    private fun updateMusicAssistantPlayers(
        serverUrl: String?,
        token: String?,
        players: List<MusicAssistantPlayerState>,
    ) {
        // Only a real LAN URL is persisted as a dial target — never a remote-mode null.
        if (!serverUrl.isNullOrBlank()) {
            storeMusicAssistantServerUrl(SendspinReceiverSession.state.value.serverId, serverUrl)
        }
        val currentPlayer = thisDeviceMusicAssistantPlayer(players)
        // Prefer the MA queue target that represents this device. On some MA
        // versions the name match is already the Universal-Player; on others
        // the exact id is the raw protocol endpoint and needs wrapper lookup.
        // If no wrapper has linked yet, expose the endpoint id so the unified
        // MA player can still follow local SendSpin playback.
        val queuePlayerId = currentPlayer?.let { player ->
            if (player.isUsableMusicAssistantQueueTarget()) {
                player.id
            } else {
                thisDeviceWrapperId(player, players) ?: player.id
            }
        }
        val targetPlayerId =
            currentPlayer?.activeGroup
                ?: currentPlayer?.syncedTo
                ?: currentPlayer?.id
        val targetPlayer = players.firstOrNull { it.id == targetPlayerId }
        val activeMemberIds =
            buildSet {
                targetPlayerId?.let(::add)
                currentPlayer?.id?.let(::add)
                targetPlayer?.groupMembers?.let(::addAll)
                players.filter { it.syncedTo == targetPlayerId }.forEach { add(it.id) }
            }
        val uiPlayers =
            players
                .filter { !it.hideInUi }
                .map { player ->
                    val isCurrent = player.id == currentPlayer?.id
                    val inActiveGroup = player.id in activeMemberIds
                    val canGroupWithTarget =
                        targetPlayerId != null &&
                            (
                                player.id in (targetPlayer?.canGroupWith ?: emptySet()) ||
                                    targetPlayerId in player.canGroupWith ||
                                    player.id in activeMemberIds
                            )
                    SendspinMusicAssistantPlayer(
                        id = player.id,
                        name = player.name,
                        type = player.type,
                        available = player.available,
                        enabled = player.enabled,
                        powered = player.powered,
                        playbackState = player.playbackState,
                        syncedTo = player.syncedTo,
                        activeGroup = player.activeGroup,
                        groupMembers = player.groupMembers,
                        canGroupWith = player.canGroupWith,
                        supportedFeatures = player.supportedFeatures,
                        isCurrent = isCurrent,
                        inActiveGroup = inActiveGroup,
                        canToggleGroup =
                            !isCurrent &&
                                targetPlayerId != null &&
                                (
                                    inActiveGroup ||
                                        (player.available && player.enabled && canGroupWithTarget)
                                ),
                    )
                }
        SendspinReceiverSession.update {
            it.copy(
                musicAssistantServerUrl = serverUrl ?: it.musicAssistantServerUrl,
                musicAssistantToken = token ?: it.musicAssistantToken,
                musicAssistantAuthState = SendspinMusicAssistantAuthState.AUTHENTICATED,
                musicAssistantLoading = false,
                musicAssistantError = null,
                musicAssistantPlayers = uiPlayers,
                musicAssistantCurrentPlayerId = currentPlayer?.id,
                musicAssistantQueuePlayerId = queuePlayerId,
                musicAssistantGroupTargetPlayerId = targetPlayerId,
                musicAssistantActiveMemberIds = activeMemberIds,
            )
        }
    }

    private fun handleMusicAssistantError(serverUrl: String?, error: Throwable) {
        val authState =
            if (error is MusicAssistantApiException && error.statusCode == 401) {
                SendspinMusicAssistantAuthState.INVALID
            } else {
                SendspinMusicAssistantAuthState.ERROR
            }
        SendspinReceiverSession.update {
            it.copy(
                musicAssistantServerUrl = serverUrl ?: it.musicAssistantServerUrl,
                musicAssistantLoading = false,
                musicAssistantAuthState = authState,
                musicAssistantError = error.message ?: "Music Assistant request failed",
            )
        }
        Timber.tag(TAG).w(error, "Music Assistant group control failed")
    }

    private fun discoverMusicAssistantServerUrl(serverId: String?): String? {
        val fallbackCandidates =
            listOfNotNull(
                storedMusicAssistantServerUrl(serverId),
                SendspinReceiverSession.state.value.musicAssistantServerUrl,
            ).distinct()
        val candidates =
            buildList {
                // A socket address alone does not identify the active connection when a
                // second server is handshaking. Try every public connection candidate and
                // let the /info server-id check below select the active MA instance.
                if (!serverId.isNullOrBlank()) addAll(inboundMusicAssistantUrls())
                addAll(fallbackCandidates)
            }.distinct()
        val client = musicAssistantClient ?: return fallbackCandidates.firstOrNull()
        for (candidate in candidates) {
            val info =
                runCatching { client.fetchInfo(candidate) }
                    .getOrNull()
                    ?: continue
            if (serverId.isNullOrBlank() || info.serverId == serverId) {
                val baseUrl = info.baseUrl.normalizeMusicAssistantUrl()
                storeMusicAssistantServerUrl(serverId, baseUrl)
                return baseUrl
            }
        }
        // Never fall back to an unverified socket peer: it may be a second server that is
        // only in the pending-handshake slot. Stored/session URLs are user- or ID-scoped.
        return fallbackCandidates.firstOrNull()
    }

    private fun inboundMusicAssistantUrls(): List<String> {
        val host = serverHost ?: return emptyList()
        return host.connections
            .asSequence()
            .filter { it.isOpen }
            .mapNotNull { host.getRemoteSocketAddress(it)?.address?.hostAddress }
            .distinct()
            .map { address ->
                val hostAddress = if (address.contains(":")) "[$address]" else address
                "http://$hostAddress:8095"
            }
            .toList()
    }

    private fun musicAssistantToken(serverId: String?, serverUrl: String?): String? {
        if (serverUrl.isNullOrBlank()) return null
        val prefs = musicAssistantPrefs()
        serverId?.takeIf { it.isNotBlank() }?.let { id ->
            val base = "$PREF_MA_TOKEN_SERVER_PREFIX$id"
            prefs.getString(scopedKey(base), null)?.takeIf { it.isNotBlank() }?.let { return it }
            prefs.getString(base, null)?.takeIf { it.isNotBlank() }?.let { return it } // legacy
        }
        tokenUrlCandidates(serverUrl).forEach { candidate ->
            val urlBase = "$PREF_MA_TOKEN_URL_PREFIX$candidate"
            prefs.getString(scopedKey(urlBase), null)?.takeIf { it.isNotBlank() }?.let { return it }
            prefs.getString(urlBase, null)?.takeIf { it.isNotBlank() }?.let { return it } // legacy
        }
        return null
    }

    private fun storeMusicAssistantToken(serverId: String?, serverUrl: String, token: String) {
        val normalizedServerUrl = serverUrl.normalizeMusicAssistantUrl()
        musicAssistantPrefs().edit().apply {
            putString(scopedKey("$PREF_MA_TOKEN_URL_PREFIX$normalizedServerUrl"), token)
            serverId?.takeIf { it.isNotBlank() }?.let { id ->
                putString(scopedKey("$PREF_MA_TOKEN_SERVER_PREFIX$id"), token)
            }
        }.apply()
    }

    private fun storedMusicAssistantServerUrl(serverId: String?): String? {
        val prefs = musicAssistantPrefs()
        serverId?.takeIf { it.isNotBlank() }?.let { id ->
            val base = "$PREF_MA_URL_SERVER_PREFIX$id"
            prefs.getString(scopedKey(base), null)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.normalizeMusicAssistantUrl() }
            prefs.getString(base, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.normalizeMusicAssistantUrl() } // legacy
        }
        prefs.getString(scopedKey(PREF_MA_LAST_URL), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.normalizeMusicAssistantUrl() }
        return prefs.getString(PREF_MA_LAST_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?.normalizeMusicAssistantUrl() // legacy
    }

    private fun storeMusicAssistantServerUrl(serverId: String?, serverUrl: String) {
        val normalizedServerUrl = serverUrl.normalizeMusicAssistantUrl()
        musicAssistantPrefs().edit().apply {
            putString(scopedKey(PREF_MA_LAST_URL), normalizedServerUrl)
            serverId?.takeIf { it.isNotBlank() }?.let { id ->
                putString(scopedKey("$PREF_MA_URL_SERVER_PREFIX$id"), normalizedServerUrl)
            }
        }.apply()
    }

    private fun musicAssistantPrefs(): SharedPreferences =
        getSharedPreferences(PREF_MA, Context.MODE_PRIVATE)

    /**
     * Lazily build (and cache) the MA REST client. The client only needs an
     * HTTP stack, not the full SendSpin server, so MA config works even when the
     * receiver hasn't bootstrapped (e.g. first-run onboarding). [base] lets the
     * bootstrap path reuse the SendSpin OkHttp config; the cold path builds fresh.
     */
    private fun ensureMusicAssistantClient(base: OkHttpClient? = null): MusicAssistantGroupClient {
        musicAssistantClient?.let { return it }
        val maHttp = (base?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .writeTimeout(java.time.Duration.ofSeconds(60))
            .callTimeout(java.time.Duration.ofSeconds(90))
            .build()
        return MusicAssistantGroupClient(maHttp)
            .also {
                it.rpcTransport = maApiTransport // keep remote control routing across rebuilds
                musicAssistantClient = it
            }
    }

    private fun updateControllerState(state: ControllerState) {
        SendspinReceiverSession.update {
            it.copy(
                supportedCommands = state.supportedCommands.toSet(),
                volume = state.volume.coerceIn(0, 100),
                muted = state.muted,
                repeat = state.repeat.presentValueOr(it.repeat),
                shuffle = state.shuffle.presentValueOr(it.shuffle),
            )
        }
    }

    private fun updateMetadataState(metadata: TrackMetadataMsg) {
        SendspinReceiverSession.update {
            val nextTitle = metadata.title.presentValueOr(it.title)
            val nextArtist = metadata.artist.presentValueOr(it.artist)
            val nextAlbum = metadata.album.presentValueOr(it.album)
            val nextArtworkUrl = metadata.artworkUrl.presentValueOr(it.artworkUrl)
            val metadataChanged =
                nextTitle != it.title ||
                    nextArtist != it.artist ||
                    nextAlbum != it.album ||
                    nextArtworkUrl != it.artworkUrl
            it.copy(
                title = nextTitle,
                artist = nextArtist,
                album = nextAlbum,
                artworkUrl = nextArtworkUrl,
                controlsDismissed =
                    if (metadataChanged &&
                        it.playbackState != SendspinReceiverPlaybackState.STOPPED
                    ) {
                        false
                    } else {
                        it.controlsDismissed
                    },
                repeat = metadata.repeat.presentValueOr(it.repeat),
                shuffle = metadata.shuffle.presentValueOr(it.shuffle),
            )
        }
    }

    private fun updateAlbumArtwork(artwork: ByteArray?) {
        val nextArtwork = artwork?.takeIf { it.isNotEmpty() }
        SendspinReceiverSession.update {
            it.copy(
                albumArtwork = nextArtwork,
                controlsDismissed = it.controlsDismissed,
            )
        }
    }

    private fun updateVisualizerState(frame: VisualizerFrame) {
        val levels = smoothVisualizerLevels(frame.toUiLevels())
        if (levels.isEmpty()) return
        SendspinReceiverSession.update {
            if (it.playbackState == SendspinReceiverPlaybackState.STOPPED && it.controlsDismissed) {
                return@update it
            }
            val startingPlayback = it.playbackState == SendspinReceiverPlaybackState.STOPPED
            it.copy(
                playbackState =
                    if (startingPlayback) {
                        SendspinReceiverPlaybackState.PLAYING
                    } else {
                        it.playbackState
                    },
                controlsDismissed = if (startingPlayback) false else it.controlsDismissed,
                visualizerLevels = levels,
            )
        }
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start foreground notification for SendspinReceiverService")
            false
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = SendspinReceiverSession.state.value
        val builder =
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(notificationTitle(state))
                .setContentText(notificationText(state))
                .setContentIntent(contentIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (state.showControls) {
            if (state.supports(SendspinControllerCommands.PREVIOUS)) {
                builder.addAction(
                    controllerAction(
                        icon = R.drawable.sendspin_ic_previous,
                        title = "Previous",
                        command = SendspinControllerCommands.PREVIOUS,
                    )
                )
            }
            val playPauseCommand =
                if (state.isPlaying) SendspinControllerCommands.PAUSE else SendspinControllerCommands.PLAY
            if (state.supports(playPauseCommand)) {
                builder.addAction(
                    controllerAction(
                        icon =
                            if (state.isPlaying) {
                                R.drawable.sendspin_ic_pause
                            } else {
                                R.drawable.sendspin_ic_play
                            },
                        title = if (state.isPlaying) "Pause" else "Play",
                        command = playPauseCommand,
                    )
                )
            }
            if (state.supports(SendspinControllerCommands.NEXT)) {
                builder.addAction(
                    controllerAction(
                        icon = R.drawable.sendspin_ic_next,
                        title = "Next",
                        command = SendspinControllerCommands.NEXT,
                    )
                )
            }
            if (state.supports(SendspinControllerCommands.STOP)) {
                builder.addAction(
                    controllerAction(
                        icon = R.drawable.sendspin_ic_stop,
                        title = "Stop",
                        command = SendspinControllerCommands.STOP,
                    )
                )
            }
        }
        return builder.build()
    }

    private fun notificationTitle(state: SendspinReceiverUiState): String =
        when {
            state.audioStalled && state.isPlaying -> "Reconnecting to Music Assistant…"
            else ->
                state.title?.takeIf(String::isNotBlank)
                    ?: if (state.connected) {
                        "Receiving Sendspin audio"
                    } else {
                        "SpatialFin is ready to receive"
                    }
        }

    private fun notificationText(state: SendspinReceiverUiState): String {
        val parts = listOfNotNull(
            state.artist?.takeIf(String::isNotBlank),
            state.album?.takeIf(String::isNotBlank),
        )
        if (parts.isNotEmpty()) return parts.joinToString(" - ")
        val server = state.serverName?.takeIf(String::isNotBlank)
        return if (server != null) "$server - Sendspin" else "$displayName - Sendspin"
    }

    private fun controllerAction(
        icon: Int,
        title: String,
        command: String,
    ): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            icon,
            title,
            controllerPendingIntent(command),
        ).build()

    private fun controllerPendingIntent(command: String): PendingIntent {
        val intent = Intent(this, SendspinReceiverService::class.java).apply {
            action = ACTION_CONTROLLER_COMMAND
            putExtra(EXTRA_CONTROLLER_COMMAND, command)
        }
        return PendingIntent.getService(
            this,
            requestCodeFor(command),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun contentIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCodeFor(command: String): Int =
        when (command) {
            SendspinControllerCommands.PLAY -> 0x5E10
            SendspinControllerCommands.PAUSE -> 0x5E11
            SendspinControllerCommands.STOP -> 0x5E12
            SendspinControllerCommands.NEXT -> 0x5E13
            SendspinControllerCommands.PREVIOUS -> 0x5E14
            else -> 0x5EFF
        }

    private fun GroupPlaybackState.toUiPlaybackState(): SendspinReceiverPlaybackState =
        when (this) {
            GroupPlaybackState.PLAYING -> SendspinReceiverPlaybackState.PLAYING
            GroupPlaybackState.PAUSED -> SendspinReceiverPlaybackState.PAUSED
            GroupPlaybackState.STOPPED -> SendspinReceiverPlaybackState.STOPPED
        }

    private fun SendspinReceiverUiState.applyOptimisticCommand(
        command: String,
        volume: Int?,
        muted: Boolean?,
    ): SendspinReceiverUiState =
        when (command) {
            SendspinControllerCommands.PLAY -> copy(playbackState = SendspinReceiverPlaybackState.PLAYING)
            SendspinControllerCommands.PAUSE -> copy(playbackState = SendspinReceiverPlaybackState.PAUSED)
            SendspinControllerCommands.STOP ->
                copy(
                    playbackState = SendspinReceiverPlaybackState.STOPPED,
                    controlsDismissed = true,
                    // Genuine stop ends the session — clear the active flag so the
                    // notification controls don't linger (a pause keeps it,
                    // because pause maps to PAUSED here, not STOP).
                    playbackStarted = false,
                    visualizerLevels = emptyList(),
                )
            SendspinControllerCommands.VOLUME -> copy(volume = (volume ?: this.volume).coerceIn(0, 100))
            SendspinControllerCommands.MUTE -> copy(muted = muted ?: this.muted)
            SendspinControllerCommands.SHUFFLE -> copy(shuffle = true)
            SendspinControllerCommands.UNSHUFFLE -> copy(shuffle = false)
            SendspinControllerCommands.REPEAT_OFF -> copy(repeat = "off")
            SendspinControllerCommands.REPEAT_ONE -> copy(repeat = "one")
            SendspinControllerCommands.REPEAT_ALL -> copy(repeat = "all")
            else -> this
        }

    private fun smoothVisualizerLevels(levels: List<Float>): List<Float> {
        if (levels.isEmpty()) return emptyList()
        val previous = smoothedVisualizerLevels
        val smoothed =
            levels.mapIndexed { index, level ->
                val target = level.coerceIn(MIN_VISUALIZER_LEVEL, 1f)
                val current = previous.getOrNull(index) ?: target
                val smoothing = if (target > current) VISUALIZER_ATTACK_SMOOTHING else VISUALIZER_RELEASE_SMOOTHING
                current + (target - current) * smoothing
            }
        smoothedVisualizerLevels = smoothed
        return smoothed
    }

    private fun <T> JsonOptional<T>.presentValueOr(current: T?): T? =
        when (this) {
            is JsonOptional.Present -> value
            else -> current
        }

    private fun VisualizerFrame.toUiLevels(): List<Float> =
        when (this) {
            is VisualizerFrame.Spectrum -> bins.toUiLevels()
            is VisualizerFrame.Loudness -> value.toFloat().normalizeVisualizerScalar().toScalarLevels()
            is VisualizerFrame.Peak -> strength.toFloat().normalizeVisualizerScalar().toScalarLevels()
            is VisualizerFrame.FPeak -> amplitude.toFloat().normalizeVisualizerScalar().toScalarLevels()
            else -> emptyList()
        }

    private fun Float.toScalarLevels(): List<Float> {
        val loudness = coerceIn(MIN_VISUALIZER_LEVEL, 1f)
        val previous = smoothedVisualizerLevels.takeIf { it.size == VISUALIZER_BIN_COUNT }
        return if (previous != null) {
            previous.map { level ->
                (level * 0.72f + loudness * 0.28f).coerceIn(MIN_VISUALIZER_LEVEL, 1f)
            }
        } else {
            VISUALIZER_SCALAR_SHAPE.map { shape ->
                (MIN_VISUALIZER_LEVEL + loudness * shape).coerceIn(MIN_VISUALIZER_LEVEL, 1f)
            }
        }
    }

    private fun ShortArray.toUiLevels(): List<Float> {
        if (isEmpty()) return emptyList()
        val selected =
            if (size == VISUALIZER_BIN_COUNT) {
                toList()
            } else {
                toList().resample(VISUALIZER_BIN_COUNT)
            }
        return selected.map { value ->
            val normalized =
                (abs(value.toInt()).toFloat() / VISUALIZER_SAMPLE_REFERENCE)
                    .coerceIn(0f, 1f)
            sqrt(normalized).coerceIn(MIN_VISUALIZER_LEVEL, 1f)
        }
    }

    private fun List<Short>.resample(targetCount: Int): List<Short> =
        List(targetCount) { index ->
            val sampleIndex = ((index.toFloat() / targetCount.toFloat()) * size)
                .toInt()
                .coerceIn(0, lastIndex)
            this[sampleIndex]
        }

    private fun Float.normalizeVisualizerScalar(): Float =
        (this / VISUALIZER_SCALAR_MAX).coerceIn(MIN_VISUALIZER_LEVEL, 1f)

    private fun String.normalizeMusicAssistantUrl(): String =
        normalizeMusicAssistantUrl(this)

    private fun tokenUrlCandidates(serverUrl: String): List<String> =
        buildList {
            val raw = serverUrl.trim()
            val rawNoSlash = raw.trimEnd('/')
            val normalized = raw.normalizeMusicAssistantUrl()
            val withoutScheme =
                normalized
                    .removePrefix("http://")
                    .removePrefix("https://")
            add(raw)
            add(rawNoSlash)
            add("$rawNoSlash/")
            add(normalized)
            add("$normalized/")
            add(withoutScheme)
            add("$withoutScheme/")
        }.filter { it.isNotBlank() }.distinct()

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Sendspin receiver",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Indicates SpatialFin can accept Sendspin streams" }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID: String = "sendspin_receiver"
        const val NOTIFICATION_ID: Int = 0x5E2D // "SEND"
        const val DEFAULT_PORT: Int = 8928

        const val EXTRA_DISPLAY_NAME: String = "sendspin.displayName"
        const val EXTRA_CLIENT_ID: String = "sendspin.clientId"
        const val EXTRA_PORT: String = "sendspin.port"
        const val EXTRA_SOFTWARE_VERSION: String = "sendspin.softwareVersion"
        const val EXTRA_CONTROLLER_COMMAND: String = "sendspin.controller.command"
        const val EXTRA_CONTROLLER_VOLUME: String = "sendspin.controller.volume"
        const val EXTRA_CONTROLLER_MUTED: String = "sendspin.controller.muted"
        const val EXTRA_MUSIC_ASSISTANT_SERVER_URL: String = "sendspin.musicAssistant.serverUrl"
        const val EXTRA_MUSIC_ASSISTANT_REMOTE_ID: String = "sendspin.musicAssistant.remoteId"
        const val EXTRA_MUSIC_ASSISTANT_USERNAME: String = "sendspin.musicAssistant.username"
        const val EXTRA_MUSIC_ASSISTANT_PASSWORD: String = "sendspin.musicAssistant.password"
        const val EXTRA_MUSIC_ASSISTANT_TOKEN: String = "sendspin.musicAssistant.token"
        const val EXTRA_MUSIC_ASSISTANT_PLAYER_ID: String = "sendspin.musicAssistant.playerId"
        const val EXTRA_MUSIC_ASSISTANT_MEMBER_ADD: String = "sendspin.musicAssistant.memberAdd"
        const val EXTRA_MUSIC_ASSISTANT_MEDIA_URI: String = "sendspin.musicAssistant.mediaUri"
        const val EXTRA_JELLYFIN_USER_ID: String = "sendspin.jellyfin.userId"

        internal const val TAG: String = "SendspinService"
        private const val ANDROID_AUDIO_OUTPUT_DELAY_MS = 80
        private const val REQUIRED_LEAD_TIME_MS = 120
        private const val MIN_BUFFER_MS = 120
        private const val CLOCK_RECOVERY_LATE_THRESHOLD_US = 1_000_000L
        private const val CLOCK_RECOVERY_FUTURE_THRESHOLD_US = 5_000_000L
        private const val CLOCK_RECOVERY_REQUIRED_OUTLIERS = 3
        private const val CLOCK_RECOVERY_LOG_INTERVAL_US = 5_000_000L
        private const val VISUALIZER_BUFFER_CAPACITY_BYTES = 4096
        private const val VISUALIZER_RATE_MAX = 15
        private const val VISUALIZER_BIN_COUNT = 16
        private const val VISUALIZER_MIN_FREQUENCY_HZ = 60
        private const val VISUALIZER_MAX_FREQUENCY_HZ = 16_000
        private const val VISUALIZER_SCALAR_MAX = 100f
        private const val VISUALIZER_SAMPLE_REFERENCE = 12_000f
        private const val VISUALIZER_ATTACK_SMOOTHING = 0.42f
        private const val VISUALIZER_RELEASE_SMOOTHING = 0.18f
        private const val MIN_VISUALIZER_LEVEL = 0.08f
        // MA's PlayerController fires `_create_or_update_universal_player` ~15 s
        // after a fresh SendSpin endpoint registers. We give it 20 s before we
        // give up and tell the user to retry — long enough to cover the timer
        // even with slight scheduler jitter, short enough that a real
        // configuration problem surfaces quickly.
        private const val WRAPPER_POLL_TIMEOUT_MS = 20_000L
        private const val FAST_POLLS = 6
        private const val FAST_POLL_INTERVAL_MS = 500L
        private const val SLOW_POLL_INTERVAL_MS = 2_000L

        // mDNS advert self-heal: retry quickly until it publishes, then
        // re-check periodically and re-publish if it ever drops.
        private const val ADVERTISE_RETRY_DELAY_MS = 3_000L
        private const val ADVERTISE_HEALTH_INTERVAL_MS = 30_000L

        /**
         * Cadence of the outbound /sendspin dial loop. While connected this is a
         * cheap state check; while disconnected it bounds Wi-Fi-blip recovery to
         * a few seconds (the official MA app's reconnect backoff tops out at 10 s).
         */
        private const val OUTBOUND_DIAL_CHECK_INTERVAL_MS = 5_000L

        /** Failed dials before the legacy mDNS advertise fallback is enabled. */
        private const val OUTBOUND_DIALS_BEFORE_ADVERTISE_FALLBACK = 6


        /**
         * WebSocket ping cadence for the outbound dial. OkHttp fails the socket
         * when a pong is missed, which is the ONLY client-side way to notice a
         * dead path (writes merely queue) — matches the official MA app's 5 s.
         */
        private const val WS_PING_INTERVAL_SECONDS = 5L

        /** Connect timeout for the LAN /sendspin dial so off-LAN failures fall back fast. */
        private const val WS_DIAL_CONNECT_TIMEOUT_SECONDS = 4L

        // Stall watchdog: with PLAYING state and zero rendered PCM, mark the UI
        // stalled after 2 ticks (4 s) and rebuild the client stack after 5
        // ticks (10 s). 10 s sits safely above the library's 500 ms track-handoff
        // gap and one-off network hiccups the buffer absorbs on its own.
        private const val STALL_CHECK_INTERVAL_MS = 2_000L
        private const val STALL_TICKS_MARK_UI = 2
        private const val STALL_TICKS_REBUILD = 5
        private val VISUALIZER_SCALAR_SHAPE =
            listOf(
                0.18f,
                0.26f,
                0.34f,
                0.48f,
                0.62f,
                0.78f,
                0.94f,
                0.72f,
                0.58f,
                0.86f,
                1.00f,
                0.70f,
                0.52f,
                0.38f,
                0.28f,
                0.20f,
            )
        private const val ACTION_CONTROLLER_COMMAND =
            "dev.jdtech.jellyfin.sendspin.action.CONTROLLER_COMMAND"
        private const val ACTION_DISMISS_CONTROLS =
            "dev.jdtech.jellyfin.sendspin.action.DISMISS_CONTROLS"
        private const val ACTION_MUSIC_ASSISTANT_REFRESH =
            "dev.jdtech.jellyfin.sendspin.action.MUSIC_ASSISTANT_REFRESH"
        private const val ACTION_MUSIC_ASSISTANT_LOGIN =
            "dev.jdtech.jellyfin.sendspin.action.MUSIC_ASSISTANT_LOGIN"
        private const val ACTION_MUSIC_ASSISTANT_SAVE_TOKEN =
            "dev.jdtech.jellyfin.sendspin.action.MUSIC_ASSISTANT_SAVE_TOKEN"
        private const val ACTION_MUSIC_ASSISTANT_SET_SERVER_URL =
            "dev.spatialfin.sendspin.action.MUSIC_ASSISTANT_SET_SERVER_URL"
        private const val ACTION_MUSIC_ASSISTANT_SET_REMOTE_ID =
            "dev.spatialfin.sendspin.action.MUSIC_ASSISTANT_SET_REMOTE_ID"
        private const val ACTION_MUSIC_ASSISTANT_SET_MEMBER =
            "dev.spatialfin.sendspin.action.MUSIC_ASSISTANT_SET_MEMBER"
        private const val ACTION_MUSIC_ASSISTANT_PLAY_MEDIA =
            "dev.spatialfin.sendspin.action.MUSIC_ASSISTANT_PLAY_MEDIA"
        private const val ACTION_MUSIC_ASSISTANT_SET_USER =
            "dev.spatialfin.sendspin.action.MUSIC_ASSISTANT_SET_USER"
        private const val REQUEST_OPEN_APP = 0x5E01
        private const val PREF_MA = "sendspin_music_assistant"
        private const val PREF_MA_LAST_URL = "last_url"
        private const val PREF_MA_TOKEN_SERVER_PREFIX = "token_server:"
        private const val PREF_MA_TOKEN_URL_PREFIX = "token_url:"
        private const val PREF_MA_URL_SERVER_PREFIX = "url_server:"
        private const val PREF_MA_REMOTE_ID = "remote_id"

        /**
         * Per-Jellyfin-user key namespace for the MA SharedPreferences. Each
         * Jellyfin user keeps an independent MA URL/token set,
         * keyed `u:<userId-or-"default">/<base>`. Reads fall back to the legacy
         * un-prefixed [base] (pre-feature, device-global) when the scoped key is
         * absent; writes only ever touch the scoped key so users stay isolated.
         */
        private fun scopedPrefKey(jellyfinUserId: String?, base: String): String {
            val scope = jellyfinUserId?.takeIf { it.isNotBlank() } ?: "default"
            return "u:$scope/$base"
        }

        private fun normalizeMusicAssistantUrl(serverUrl: String): String {
            val trimmed = serverUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) return trimmed
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }

        fun start(
            context: Context,
            displayName: String,
            clientId: String,
            port: Int = DEFAULT_PORT,
            softwareVersion: String? = null,
        ) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_CLIENT_ID, clientId)
                putExtra(EXTRA_PORT, port)
                softwareVersion?.let { putExtra(EXTRA_SOFTWARE_VERSION, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SendspinReceiverService::class.java))
        }

        fun sendControllerCommand(
            context: Context,
            command: String,
            volume: Int? = null,
            muted: Boolean? = null,
        ) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_CONTROLLER_COMMAND
                putExtra(EXTRA_CONTROLLER_COMMAND, command)
                volume?.let { putExtra(EXTRA_CONTROLLER_VOLUME, it.coerceIn(0, 100)) }
                muted?.let { putExtra(EXTRA_CONTROLLER_MUTED, it) }
            }
            context.startService(intent)
        }

        fun dismissControls(context: Context) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_DISMISS_CONTROLS
            }
            context.startService(intent)
        }

        fun refreshMusicAssistantGroups(context: Context, jellyfinUserId: String?) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_MUSIC_ASSISTANT_REFRESH
                putExtra(EXTRA_JELLYFIN_USER_ID, jellyfinUserId)
            }
            context.startService(intent)
        }

        fun loginMusicAssistant(
            context: Context,
            serverUrl: String,
            username: String,
            password: String,
            jellyfinUserId: String?,
        ) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_MUSIC_ASSISTANT_LOGIN
                putExtra(EXTRA_MUSIC_ASSISTANT_SERVER_URL, serverUrl)
                putExtra(EXTRA_MUSIC_ASSISTANT_USERNAME, username)
                putExtra(EXTRA_MUSIC_ASSISTANT_PASSWORD, password)
                putExtra(EXTRA_JELLYFIN_USER_ID, jellyfinUserId)
            }
            context.startService(intent)
        }

        fun saveMusicAssistantToken(
            context: Context,
            serverUrl: String,
            token: String,
            jellyfinUserId: String?,
        ) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_MUSIC_ASSISTANT_SAVE_TOKEN
                putExtra(EXTRA_MUSIC_ASSISTANT_SERVER_URL, serverUrl)
                putExtra(EXTRA_MUSIC_ASSISTANT_TOKEN, token)
                putExtra(EXTRA_JELLYFIN_USER_ID, jellyfinUserId)
            }
            context.startService(intent)
        }

        fun setMusicAssistantServerUrl(
            context: Context,
            serverUrl: String,
            jellyfinUserId: String?,
        ) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_MUSIC_ASSISTANT_SET_SERVER_URL
                putExtra(EXTRA_MUSIC_ASSISTANT_SERVER_URL, serverUrl)
                putExtra(EXTRA_JELLYFIN_USER_ID, jellyfinUserId)
            }
            context.startService(intent)
        }

        /**
         * Store the Music Assistant Remote ID for off-LAN remote access (from
         * Music Assistant → Settings → Remote Access). Accepts the hyphenated display
         * form; the service validates and normalises it. Scoped to [jellyfinUserId].
         */
        fun setMusicAssistantRemoteId(
            context: Context,
            remoteId: String,
            jellyfinUserId: String?,
        ) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_MUSIC_ASSISTANT_SET_REMOTE_ID
                putExtra(EXTRA_MUSIC_ASSISTANT_REMOTE_ID, remoteId)
                putExtra(EXTRA_JELLYFIN_USER_ID, jellyfinUserId)
            }
            context.startService(intent)
        }

        fun setMusicAssistantGroupMember(
            context: Context,
            playerId: String,
            add: Boolean,
            jellyfinUserId: String?,
        ) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_MUSIC_ASSISTANT_SET_MEMBER
                putExtra(EXTRA_MUSIC_ASSISTANT_PLAYER_ID, playerId)
                putExtra(EXTRA_MUSIC_ASSISTANT_MEMBER_ADD, add)
                putExtra(EXTRA_JELLYFIN_USER_ID, jellyfinUserId)
            }
            context.startService(intent)
        }

        fun playMusicAssistantMedia(context: Context, mediaUri: String, jellyfinUserId: String?) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_MUSIC_ASSISTANT_PLAY_MEDIA
                putExtra(EXTRA_MUSIC_ASSISTANT_MEDIA_URI, mediaUri)
                putExtra(EXTRA_JELLYFIN_USER_ID, jellyfinUserId)
            }
            context.startService(intent)
        }

        /**
         * Re-scope the receiver's MA config to a (possibly new) Jellyfin user
         * and reload the session for that user's stored URL/token. Call on every
         * active-session change so a user switch never leaves another user's MA
         * token live in the controls UI.
         */
        fun setMusicAssistantUser(context: Context, jellyfinUserId: String?) {
            val intent = Intent(context, SendspinReceiverService::class.java).apply {
                action = ACTION_MUSIC_ASSISTANT_SET_USER
                putExtra(EXTRA_JELLYFIN_USER_ID, jellyfinUserId)
            }
            context.startService(intent)
        }

        /**
         * Persist per-user MA config straight to SharedPreferences WITHOUT
         * starting the receiver service — used by companion sync to apply config
         * for a user who isn't currently casting. Writes the same scoped keys the
         * running service reads (LAST_URL fallback + per-URL token), so the next
         * time the service runs as [jellyfinUserId] it picks the config up.
         */
        fun storeMusicAssistantConfig(
            context: Context,
            jellyfinUserId: String?,
            serverUrl: String,
            token: String?,
        ) {
            val normalizedServerUrl = normalizeMusicAssistantUrl(serverUrl)
            if (normalizedServerUrl.isBlank()) return
            context.getSharedPreferences(PREF_MA, Context.MODE_PRIVATE).edit().apply {
                putString(scopedPrefKey(jellyfinUserId, PREF_MA_LAST_URL), normalizedServerUrl)
                token?.takeIf { it.isNotBlank() }?.let {
                    putString(
                        scopedPrefKey(jellyfinUserId, "$PREF_MA_TOKEN_URL_PREFIX$normalizedServerUrl"),
                        it,
                    )
                }
            }.apply()
        }

    }
}
