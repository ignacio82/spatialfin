package dev.jdtech.jellyfin.sendspin.receiver.remote

import android.content.Context
import dev.jdtech.jellyfin.sendspin.receiver.MaRpcTransport
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService.Companion.TAG
import okhttp3.OkHttpClient
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Establishes a WebRTC peer connection to a Music Assistant gateway addressed by
 * [RemoteId], brokered by the cloud signaling server, and tunnels the SendSpin protocol
 * over its `sendspin` data channel. The headline off-LAN remote-access feature.
 *
 * Flow (mirrors the official MA mobile app's `WebRTCConnectionManager`):
 *  1. signaling open → send `connect-request{remoteId}`
 *  2. `connected{sessionId, iceServers}` → build PeerConnection + two data channels
 *     (`ma-api` reliable, `sendspin`) → create + send SDP offer
 *  3. `answer` → setRemoteDescription; trickle ICE both ways
 *  4. `sendspin` channel opens → start the loopback relay and hand the URL back so the
 *     stock SendSpinClient can dial it ([onSendspinReady])
 *
 * Per the official app, the `sendspin` channel skips per-channel auth (the SendSpinClient's
 * normal `client/hello`-first handshake is exactly right). Connection-level auth and all
 * Music Assistant control ride the `ma-api` channel — created here for SDP parity but only
 * wired up in Phase 2 (remote control parity); Phase 1 delivers audio receive.
 *
 * All PeerConnection mutations are serialised on a single-thread executor; data-channel
 * sends are marshalled there too, off the loopback relay's worker thread.
 */
internal class MaWebRtcConnection(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
    private val remoteId: RemoteId,
    /** Device name presented to the MA `ma-api` auth handshake. */
    private val deviceName: String,
    /** Supplies the MA token for `ma-api` auth, or null to skip auth. */
    private val tokenProvider: () -> String?,
    /** Invoked once the sendspin transport is ready; arg is the `ws://127.0.0.1:<port>` URL. */
    private val onSendspinReady: (loopbackUrl: String) -> Unit,
    /** Invoked with the `ma-api` JSON-RPC transport and HTTP proxy when it opens, and null on teardown. */
    private val onMaApiReady: (MaRpcTransport?, MaWebRtcHttpProxy?) -> Unit,
    /** Invoked on terminal failure/teardown with a short reason. */
    private val onClosed: (reason: String) -> Unit,
) : MaSignalingClient.Listener {

    private val codec = SignalingCodec()
    private val signaling = MaSignalingClient(httpClient, codec, this)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ma-webrtc") }

    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var maApiChannel: DataChannel? = null
    @Volatile private var maApiClient: MaApiChannelClient? = null
    @Volatile private var sendspinChannel: DataChannel? = null
    @Volatile private var relay: SendspinLoopbackRelay? = null
    @Volatile private var loggedFirstSendspinIn = false
    @Volatile private var loggedFirstSendspinOut = false

    @Volatile private var sessionId: String? = null
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var closed = false
    private val pendingRemoteIce = ArrayList<IceCandidate>()

    fun start() {
        signaling.connect()
    }

    fun close(reason: String) {
        if (closed) return
        closed = true
        worker.execute { teardown(reason) }
        worker.shutdown()
    }

    private fun teardown(reason: String) {
        Timber.tag(TAG).i("WebRTC remote teardown: %s", reason)
        runCatching { relay?.stop() }
        relay = null
        runCatching { maApiClient?.close() }
        maApiClient = null
        runCatching { onMaApiReady(null, null) }
        // Dispose native resources (channels → peer connection → factory) so reconnects
        // don't leak. The one-time PeerConnectionFactory.initialize() stays process-global.
        runCatching { sendspinChannel?.dispose() }
        runCatching { maApiChannel?.dispose() }
        sendspinChannel = null
        maApiChannel = null
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { signaling.close(reason) }
        onClosed(reason)
    }

    private fun fail(reason: String) {
        if (closed) return
        closed = true
        worker.execute { teardown(reason) }
        worker.shutdown()
    }

    // ── Signaling callbacks ───────────────────────────────────────────────────

    override fun onSignalingOpen() {
        worker.execute {
            if (closed) return@execute
            signaling.send(codec.encodeConnectRequest(remoteId))
        }
    }

    override fun onSignalingMessage(message: SignalingInbound) {
        worker.execute {
            if (closed) return@execute
            when (message) {
                is SignalingInbound.Connected -> onConnected(message)
                is SignalingInbound.Answer -> onAnswer(message)
                is SignalingInbound.RemoteIce -> onRemoteIce(message)
                is SignalingInbound.Error -> fail("signaling_error: ${message.error}")
                SignalingInbound.PeerDisconnected -> fail("peer_disconnected")
                SignalingInbound.Ping -> {} // handled in the signaling client
                is SignalingInbound.Unknown -> Timber.tag(TAG).d("Signaling: ignoring %s", message.type)
            }
        }
    }

    override fun onSignalingClosed(reason: String) {
        fail("signaling_closed: $reason")
    }

    override fun onSignalingFailure(error: Throwable) {
        fail("signaling_failure: ${error.message}")
    }

    // ── WebRTC establishment (all on the worker thread) ───────────────────────

    private fun onConnected(message: SignalingInbound.Connected) {
        if (peerConnection != null) return // ignore a duplicate `connected`
        sessionId = message.sessionId
        Timber.tag(TAG).i(
            "WebRTC remote: connected sessionId=%s iceServers=%d",
            message.sessionId, message.iceServers.size,
        )
        ensureFactoryInit(appContext)
        val pcFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            .also { factory = it }

        val iceServers = message.iceServers.mapNotNull { srv ->
            srv.urls.takeIf { it.isNotEmpty() }?.let { urls ->
                PeerConnection.IceServer.builder(urls)
                    .setUsername(srv.username.orEmpty())
                    .setPassword(srv.credential.orEmpty())
                    .createIceServer()
            }
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val pc = pcFactory.createPeerConnection(config, pcObserver)
        if (pc == null) {
            fail("peer_connection_null")
            return
        }
        peerConnection = pc

        // Both channels must be in the SDP offer (creating later forces renegotiation).
        // ma-api: reliable/ordered for the MA JSON-RPC API (remote control parity).
        val apiChannel = pc.createDataChannel("ma-api", DataChannel.Init().apply { ordered = true })
        maApiChannel = apiChannel
        val apiClient = MaApiChannelClient(
            deviceName = deviceName,
            tokenProvider = tokenProvider,
            send = { text -> worker.execute { sendOnMaApi(text) } },
        )
        maApiClient = apiClient
        apiChannel.registerObserver(maApiObserver(apiChannel, apiClient))
        // sendspin: ordered audio + protocol. Reliability is a documented tuning knob —
        // the official app shipped both ordered=true and (for lossy cellular) unreliable
        // ordered=false/maxRetransmits=0; validate on-device before changing.
        val sendspin = pc.createDataChannel("sendspin", DataChannel.Init().apply { ordered = true })
        sendspinChannel = sendspin
        sendspin.registerObserver(sendspinObserver(sendspin))

        pc.createOffer(
            object : SimpleSdpObserver("createOffer") {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    worker.execute {
                        if (closed) return@execute
                        pc.setLocalDescription(SimpleSdpObserver("setLocal"), sdp)
                        val sid = sessionId
                        if (sid == null) {
                            fail("missing_session_id")
                            return@execute
                        }
                        signaling.send(
                            codec.encodeOffer(
                                remoteId,
                                sid,
                                SessionDescriptionData(sdp.description, "offer"),
                            ),
                        )
                    }
                }
            },
            MediaConstraints(),
        )
    }

    private fun onAnswer(message: SignalingInbound.Answer) {
        val pc = peerConnection ?: return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, message.data.sdp)
        pc.setRemoteDescription(
            object : SimpleSdpObserver("setRemote") {
                override fun onSetSuccess() {
                    worker.execute {
                        remoteDescriptionSet = true
                        pendingRemoteIce.forEach { pc.addIceCandidate(it) }
                        pendingRemoteIce.clear()
                    }
                }
            },
            answer,
        )
    }

    private fun onRemoteIce(message: SignalingInbound.RemoteIce) {
        val candidate = IceCandidate(
            message.data.sdpMid.orEmpty(),
            message.data.sdpMLineIndex ?: 0,
            message.data.candidate,
        )
        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingRemoteIce.add(candidate)
        }
    }

    // ── sendspin data channel → loopback relay bridge ─────────────────────────

    private fun sendspinObserver(channel: DataChannel) =
        object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                worker.execute {
                    if (closed) return@execute
                    when (channel.state()) {
                        DataChannel.State.OPEN -> startRelay()
                        DataChannel.State.CLOSED -> fail("sendspin_channel_closed")
                        else -> {}
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                // Runs on the WebRTC network thread; copy the buffer before it's recycled.
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                if (!loggedFirstSendspinIn) {
                    loggedFirstSendspinIn = true
                    Timber.tag(TAG).i("sendspin << first frame (binary=%b len=%d)", buffer.binary, data.size)
                }
                val r = relay ?: return
                if (buffer.binary) r.deliverBinary(data) else r.deliverText(String(data, Charsets.UTF_8))
            }
        }

    private fun startRelay() {
        if (relay != null) return
        val r = SendspinLoopbackRelay(
            onOutboundText = { text -> worker.execute { sendOnSendspin(text.toByteArray(Charsets.UTF_8), binary = false) } },
            onOutboundBinary = { bytes -> worker.execute { sendOnSendspin(bytes, binary = true) } },
        )
        val port = r.start()
        val url = r.loopbackUrl()
        if (port <= 0 || url == null) {
            fail("relay_bind_failed")
            return
        }
        relay = r
        Timber.tag(TAG).i("WebRTC remote: sendspin channel open, relay at %s", url)
        onSendspinReady(url)
    }

    private fun sendOnSendspin(data: ByteArray, binary: Boolean) {
        if (closed) return
        if (!loggedFirstSendspinOut) {
            loggedFirstSendspinOut = true
            Timber.tag(TAG).i("sendspin >> first frame (binary=%b len=%d)", binary, data.size)
        }
        runCatching {
            sendspinChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(data), binary))
        }.onFailure { Timber.tag(TAG).d(it, "sendspin channel send failed") }
    }

    // ── ma-api data channel → JSON-RPC client bridge ──────────────────────────

    private fun maApiObserver(channel: DataChannel, apiClient: MaApiChannelClient) =
        object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                worker.execute {
                    if (closed) return@execute
                    when (channel.state()) {
                        DataChannel.State.OPEN -> {
                            Timber.tag(TAG).i("WebRTC remote: ma-api channel open")
                            onMaApiReady(apiClient.transport, apiClient.httpProxy)
                        }
                        DataChannel.State.CLOSED -> onMaApiReady(null, null)
                        else -> {}
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                // ma-api is JSON-RPC text. Copy off the WebRTC thread buffer and feed the client.
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                apiClient.onMessage(String(data, Charsets.UTF_8))
            }
        }

    private fun sendOnMaApi(text: String) {
        if (closed) return
        runCatching {
            maApiChannel?.send(
                DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false),
            )
        }.onFailure { Timber.tag(TAG).d(it, "ma-api channel send failed") }
    }

    // ── PeerConnection.Observer ───────────────────────────────────────────────

    private val pcObserver =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Timber.tag(TAG).d("WebRTC remote: ICE %s", state)
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED
                ) {
                    fail("ice_$state")
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.FAILED ||
                    newState == PeerConnection.PeerConnectionState.CLOSED
                ) {
                    fail("pc_$newState")
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onIceCandidate(candidate: IceCandidate) {
                val sid = sessionId ?: return
                signaling.send(
                    codec.encodeIceCandidate(
                        remoteId,
                        sid,
                        IceCandidateData(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex),
                    ),
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onAddStream(stream: MediaStream) {}

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(channel: DataChannel) {
                // Gateway may create its own ma-api channel; Phase 2 will adopt it for control.
                Timber.tag(TAG).d("WebRTC remote: server data channel '%s'", channel.label())
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }

    private companion object {
        @Volatile private var factoryInitialized = false

        @Synchronized
        fun ensureFactoryInit(context: Context) {
            if (factoryInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            factoryInitialized = true
        }
    }
}

/** SdpObserver with no-op defaults so call sites override only what they need. */
private open class SimpleSdpObserver(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        Timber.tag(TAG).w("WebRTC remote: %s create failed: %s", tag, error)
    }
    override fun onSetFailure(error: String?) {
        Timber.tag(TAG).w("WebRTC remote: %s set failed: %s", tag, error)
    }
}
