package dev.jdtech.jellyfin.data.musicassistant.api

import dev.jdtech.jellyfin.data.musicassistant.api.Event as EventWrapper
import dev.jdtech.jellyfin.data.musicassistant.utils.SessionState
import dev.jdtech.jellyfin.data.musicassistant.utils.myJson
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpServiceClient(
    private val httpClient: OkHttpClient,
) : ServiceClient, CoroutineScope {

    private val supervisorJob = SupervisorJob()
    override val coroutineContext = supervisorJob + Dispatchers.IO

    private val messageDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val socketLock = Any()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected.Initial)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _activeSocketGeneration = MutableStateFlow(0L)
    override val activeSocketGeneration: StateFlow<Long> = _activeSocketGeneration.asStateFlow()

    private val _isReadyForCommands = MutableStateFlow(false)
    override val isReadyForCommands: StateFlow<Boolean> = _isReadyForCommands.asStateFlow()

    private val _serverBaseUrl = MutableStateFlow<String?>(null)
    override val serverBaseUrl: StateFlow<String?> = _serverBaseUrl.asStateFlow()

    private val _events = MutableSharedFlow<ScopedServiceEvent>(extraBufferCapacity = 100)
    override val events: Flow<ScopedServiceEvent> = _events.asSharedFlow()

    private val _foregroundEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val foregroundEvents: Flow<Unit> = _foregroundEvents.asSharedFlow()

    private var webSocket: WebSocket? = null
    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private var pendingAuthToken: String? = null
    private var awaitingServerInfo = false

    private val rpcEngine = RpcEngine(
        onAuthError = {
            Timber.e("Auth error received from server")
            disconnectInternal(
                targetState = SessionState.Disconnected.AuthError,
                reason = "Authentication failed",
                cause = CancellationException("MA authentication failed"),
            )
        },
        onError = { error ->
            Timber.e("RPC Error: $error")
        },
    )

    override suspend fun sendRequest(request: Request): Result<Answer> =
        suspendCancellableCoroutine { cont ->
            rpcEngine.registerCallback(request.messageId) { result ->
                if (cont.isActive) {
                    cont.resume(result)
                }
            }

            val requestStr = myJson.encodeToJsonElement(Request.serializer(), request).toString()
            val sent = synchronized(socketLock) { webSocket }?.send(requestStr) ?: false
            if (!sent) {
                rpcEngine.removeCallback(request.messageId)
                if (cont.isActive) {
                    cont.resume(Result.failure(IllegalStateException("WebSocket is not connected")))
                }
            }

            cont.invokeOnCancellation {
                rpcEngine.removeCallback(request.messageId)
            }
        }

    override suspend fun login(username: String, password: String) = Unit

    override suspend fun authorize(token: String, isAutoLogin: Boolean) = Unit

    override fun logout() {
        disconnectByUser()
    }

    override fun resolveImageUrl(
        path: String,
        provider: String,
        isRemotelyAccessible: Boolean,
    ): String? {
        if (isRemotelyAccessible && path.startsWith("http")) return path

        dev.jdtech.jellyfin.data.musicassistant.utils.MaImageProxyOverride.resolver
            ?.invoke(path, provider)
            ?.let { return it }

        val baseUrl = _serverBaseUrl.value ?: return null
        return "$baseUrl/imageproxy?path=${path}&provider=${provider}"
    }

    override fun rebaseServerImageUrl(rawUrl: String): String? = rawUrl

    override fun onAppForeground() {
        launch { _foregroundEvents.emit(Unit) }
    }

    override fun onAppBackground() = Unit
    override fun onExternalConsumerActive() = Unit
    override fun onPlaybackActive() = Unit
    override fun onExternalConsumerInactive() = Unit
    override fun onPlaybackInactive() = Unit

    override fun disconnectByUser() {
        disconnectInternal(
            targetState = SessionState.Disconnected.ByUser,
            reason = "Disconnected by user",
            cause = CancellationException("MA disconnected by user"),
        )
    }

    override fun disconnectForRebind() {
        disconnectInternal(
            targetState = SessionState.Disconnected.Initial,
            reason = "Disconnected for session rebind",
            cause = CancellationException("MA disconnected for session rebind"),
        )
    }

    private fun disconnectInternal(
        targetState: SessionState,
        reason: String,
        cause: Throwable,
    ) {
        val oldSocket: WebSocket?
        synchronized(socketLock) {
            oldSocket = webSocket
            webSocket = null
            pendingAuthToken = null
            awaitingServerInfo = false
            _activeSocketGeneration.value += 1L
            _isReadyForCommands.value = false
            _serverBaseUrl.value = null
            _sessionState.update { targetState }
        }
        oldSocket?.close(1000, reason)
        rpcEngine.clear(cause)
    }

    private fun sendAuth(capturedSocket: WebSocket, token: String) {
        val request = Request(
            command = APICommands.AUTH,
            args = buildJsonObject { put("token", JsonPrimitive(token)) },
        )
        rpcEngine.registerCallback(request.messageId) { result ->
            val answer = result.getOrNull() ?: return@registerCallback
            if (!answer.json.containsKey("error_code")) {
                synchronized(socketLock) {
                    if (this.webSocket === capturedSocket) {
                        Timber.i("MA WebSocket authenticated")
                        _isReadyForCommands.value = true
                    }
                }
            }
        }
        val requestStr = myJson.encodeToJsonElement(Request.serializer(), request).toString()
        val sent =
            synchronized(socketLock) {
                if (this.webSocket === capturedSocket) capturedSocket.send(requestStr) else false
            }
        if (!sent) {
            rpcEngine.removeCallback(request.messageId)
        }
    }

    override fun connect(connection: ConnectionInfo) {
        val wsUrl =
            connection.webUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        val request = okhttp3.Request.Builder().url(wsUrl).build()

        val previousSocket: WebSocket?
        val socketGen: Long
        synchronized(socketLock) {
            previousSocket = webSocket
            webSocket = null
            _isReadyForCommands.value = false
            _sessionState.update { SessionState.Connecting }
            _serverBaseUrl.value = connection.webUrl
            pendingAuthToken = connection.token?.takeIf { it.isNotBlank() }
            awaitingServerInfo = pendingAuthToken != null
            socketGen = _activeSocketGeneration.value + 1L
            _activeSocketGeneration.value = socketGen
        }
        previousSocket?.cancel()
        rpcEngine.clear(CancellationException("MA reconnecting"))

        val createdSocket =
            httpClient.newBuilder()
                .pingInterval(10, TimeUnit.SECONDS)
                .build()
                .newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            synchronized(socketLock) {
                                if (this@OkHttpServiceClient.webSocket !== webSocket) return
                                Timber.i("WebSocket connected (gen=%d)", socketGen)
                                _sessionState.update { SessionState.Connected.Direct(connection) }
                                if (pendingAuthToken == null) {
                                    _isReadyForCommands.value = true
                                }
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            launch(messageDispatcher) {
                                val isCurrent = synchronized(socketLock) {
                                    this@OkHttpServiceClient.webSocket === webSocket
                                }
                                if (!isCurrent) return@launch
                                try {
                                    val element = jsonFormat.parseToJsonElement(text)
                                    if (element is JsonObject) {
                                        val authTokenToSend: String? =
                                            synchronized(socketLock) {
                                                if (this@OkHttpServiceClient.webSocket !== webSocket) {
                                                    return@launch
                                                }
                                                if (awaitingServerInfo && element["message_id"] == null) {
                                                    awaitingServerInfo = false
                                                    pendingAuthToken
                                                } else {
                                                    null
                                                }
                                            }
                                        if (authTokenToSend != null) {
                                            sendAuth(webSocket, authTokenToSend)
                                            return@launch
                                        }
                                        val isHandled = rpcEngine.handleResponse(element)
                                        if (!isHandled) {
                                            try {
                                                val eventWrapper = EventWrapper(element)
                                                val concreteEvent = eventWrapper.event()
                                                if (concreteEvent != null) {
                                                    val stillCurrent = synchronized(socketLock) {
                                                        this@OkHttpServiceClient.webSocket === webSocket
                                                    }
                                                    if (stillCurrent) {
                                                        _events.emit(
                                                            ScopedServiceEvent(
                                                                socketGeneration = socketGen,
                                                                event = concreteEvent,
                                                            )
                                                        )
                                                    }
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
                            val wasCurrent =
                                synchronized(socketLock) {
                                    if (this@OkHttpServiceClient.webSocket !== webSocket) {
                                        false
                                    } else {
                                        this@OkHttpServiceClient.webSocket = null
                                        pendingAuthToken = null
                                        awaitingServerInfo = false
                                        _activeSocketGeneration.value += 1L
                                        _isReadyForCommands.value = false
                                        _sessionState.update { SessionState.Disconnected.Initial }
                                        true
                                    }
                                }
                            if (wasCurrent) {
                                Timber.i("WebSocket closed: $code $reason")
                                rpcEngine.clear(CancellationException("MA WebSocket closed: $code"))
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            val wasCurrent =
                                synchronized(socketLock) {
                                    if (this@OkHttpServiceClient.webSocket !== webSocket) {
                                        false
                                    } else {
                                        this@OkHttpServiceClient.webSocket = null
                                        pendingAuthToken = null
                                        awaitingServerInfo = false
                                        _activeSocketGeneration.value += 1L
                                        _isReadyForCommands.value = false
                                        _sessionState.update {
                                            SessionState.Disconnected.Error(
                                                (t as? Exception) ?: Exception(t)
                                            )
                                        }
                                        true
                                    }
                                }
                            if (wasCurrent) {
                                Timber.e(t, "WebSocket failure")
                                rpcEngine.clear(t)
                            }
                        }
                    },
                )

        synchronized(socketLock) {
            if (_activeSocketGeneration.value == socketGen) {
                webSocket = createdSocket
            } else {
                // Another disconnect/connect raced ahead before newWebSocket returned.
                createdSocket.cancel()
            }
        }
    }
}
