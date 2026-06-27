package dev.jdtech.jellyfin.data.musicassistant.api

import dev.jdtech.jellyfin.data.musicassistant.api.Event as EventWrapper
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.Event
import dev.jdtech.jellyfin.data.musicassistant.utils.SessionState
import dev.jdtech.jellyfin.data.musicassistant.utils.myJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpServiceClient(
    private val httpClient: OkHttpClient
) : ServiceClient, CoroutineScope {

    private val supervisorJob = SupervisorJob()
    override val coroutineContext = supervisorJob + Dispatchers.IO

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected.Initial)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _isReadyForCommands = MutableStateFlow(false)
    override val isReadyForCommands: StateFlow<Boolean> = _isReadyForCommands.asStateFlow()

    private val _serverBaseUrl = MutableStateFlow<String?>(null)
    override val serverBaseUrl: StateFlow<String?> = _serverBaseUrl.asStateFlow()

    private val _events = MutableSharedFlow<Event<out Any>>(extraBufferCapacity = 100)
    override val events: Flow<Event<out Any>> = _events.asSharedFlow()

    private val _foregroundEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val foregroundEvents: Flow<Unit> = _foregroundEvents.asSharedFlow()


    private var webSocket: WebSocket? = null
    private val jsonFormat = Json { ignoreUnknownKeys = true }

    // MA auth handshake: the server sends `server_info` on connect, then the
    // first command MUST be `auth` with the token. We hold the token between
    // connect() and the server_info arrival, and only mark the socket
    // command-ready once auth succeeds.
    private var pendingAuthToken: String? = null
    private var awaitingServerInfo = false

    private val rpcEngine = RpcEngine(
        onAuthError = {
            Timber.e("Auth error received from server")
            disconnectByUser()
        },
        onError = { error ->
            Timber.e("RPC Error: $error")
        }
    )

    override suspend fun sendRequest(request: Request): Result<Answer> = suspendCancellableCoroutine { cont ->
        rpcEngine.registerCallback(request.messageId) { answer ->
            cont.resume(Result.success(answer))
        }

        val requestStr = myJson.encodeToJsonElement(Request.serializer(), request).toString()
        val sent = webSocket?.send(requestStr) ?: false
        if (!sent) {
            rpcEngine.removeCallback(request.messageId)
            cont.resumeWithException(IllegalStateException("WebSocket is not connected"))
        }

        cont.invokeOnCancellation {
            rpcEngine.removeCallback(request.messageId)
        }
    }

    override suspend fun login(username: String, password: String) {
        // MA uses explicit login over RPC if required.
    }

    override suspend fun authorize(token: String, isAutoLogin: Boolean) {
        // Implementation omitted for brevity.
    }

    override fun logout() {
        disconnectByUser()
    }

    override fun resolveImageUrl(path: String, provider: String, isRemotelyAccessible: Boolean): String? {
        if (isRemotelyAccessible && path.startsWith("http")) return path
        
        dev.jdtech.jellyfin.data.musicassistant.utils.MaImageProxyOverride.resolver?.invoke(path, provider)?.let { return it }
        
        val baseUrl = _serverBaseUrl.value ?: return null
        return "$baseUrl/imageproxy?path=${path}&provider=${provider}"
    }

    override fun rebaseServerImageUrl(rawUrl: String): String? {
        return rawUrl
    }

    override fun onAppForeground() {
        launch { _foregroundEvents.emit(Unit) }
    }

    override fun onAppBackground() { }
    override fun onExternalConsumerActive() { }
    override fun onPlaybackActive() { }
    override fun onExternalConsumerInactive() { }
    override fun onPlaybackInactive() { }

    override fun disconnectByUser() {
        _sessionState.update { SessionState.Disconnected.ByUser }
        _isReadyForCommands.value = false
        webSocket?.close(1000, "Disconnected by user")
        webSocket = null
        rpcEngine.clear()
    }

    /**
     * Send the MA `auth` command (the mandatory first command on an
     * auth-enabled server). On success the server replies with a result that
     * has no `error_code`; that's when the socket becomes command-ready. An
     * `error_code 20` reply is routed by [RpcEngine] to its auth-error handler,
     * which disconnects so the caller can retry.
     */
    private fun sendAuth(token: String) {
        val request = Request(
            command = APICommands.AUTH,
            args = buildJsonObject { put("token", JsonPrimitive(token)) },
        )
        rpcEngine.registerCallback(request.messageId) { answer ->
            if (!answer.json.containsKey("error_code")) {
                Timber.i("MA WebSocket authenticated")
                _isReadyForCommands.value = true
            }
        }
        val requestStr = myJson.encodeToJsonElement(Request.serializer(), request).toString()
        if (webSocket?.send(requestStr) != true) {
            rpcEngine.removeCallback(request.messageId)
        }
    }

    override fun connect(connection: ConnectionInfo) {
        _sessionState.update { SessionState.Connecting }
        _serverBaseUrl.value = connection.webUrl

        pendingAuthToken = connection.token?.takeIf { it.isNotBlank() }
        awaitingServerInfo = pendingAuthToken != null

        val wsUrl = connection.webUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        val request = okhttp3.Request.Builder().url(wsUrl).build()

        webSocket?.cancel()
        webSocket = httpClient.newBuilder()
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
            .newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.i("WebSocket connected")
                    _sessionState.update { SessionState.Connected.Direct(connection) }
                    // Auth-required servers: stay not-ready until the `auth`
                    // command (sent after server_info) succeeds. Open servers:
                    // ready immediately.
                    if (pendingAuthToken == null) {
                        _isReadyForCommands.value = true
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    launch {
                        try {
                            val element = jsonFormat.parseToJsonElement(text)
                            if (element is JsonObject) {
                                // The first message is `server_info`; reply with
                                // the `auth` command before anything else.
                                if (awaitingServerInfo && element["message_id"] == null) {
                                    awaitingServerInfo = false
                                    pendingAuthToken?.let { sendAuth(it) }
                                    return@launch
                                }
                                val isHandled = rpcEngine.handleResponse(element)
                                if (!isHandled) {
                                    // Try parse as Event
                                    try {
                                        val eventWrapper = EventWrapper(element as JsonObject)
                                        val concreteEvent = eventWrapper.event()
                                        if (concreteEvent != null) {
                                            _events.emit(concreteEvent)
                                        }
                                    } catch (e: Exception) {
                                        Timber.w(e, "Failed to decode event: $text")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error processing WebSocket message")
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.i("WebSocket closed: $code $reason")
                    _isReadyForCommands.value = false
                    _sessionState.update { SessionState.Disconnected.Initial }
                    rpcEngine.clear()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "WebSocket failure")
                    _isReadyForCommands.value = false
                    _sessionState.update { SessionState.Disconnected.Error(Exception(t)) }
                    rpcEngine.clear()
                }
            })
    }
}
