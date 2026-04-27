package dev.spatialfin.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.network.SmbPathNormalizer
import dev.jdtech.jellyfin.network.SmbConnectionTarget
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionNetworkShare
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingEnvelope
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingInfo
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingPayload
import dev.jdtech.jellyfin.models.companion.CompanionUser
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.applyCompanionPreference
import dev.jdtech.jellyfin.work.CompanionSyncWorker
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import timber.log.Timber

private const val TV_COMPANION_PORT = 41230
private const val TV_COMPANION_PAIRING_DURATION_MS = 10 * 60 * 1000L
private const val TV_COMPANION_NSD_TYPE = "_spatialfin-tv._tcp."
private const val TV_COMPANION_NSD_NAME = "SpatialFin TV"

private data class ImportedSession(
    val serverId: String,
    val baseUrl: String,
    val userId: UUID,
    val accessToken: String?,
)

sealed class TvCompanionState {
    data object Idle : TvCompanionState()
    data class Ready(
        val payload: CompanionTvPairingPayload,
        val manualCode: String,
        val localUrls: List<String>,
        val qrContent: String,
    ) : TvCompanionState()
    data object Applying : TvCompanionState()
    data object Success : TvCompanionState()
    data class Error(val message: String) : TvCompanionState()
}

@HiltViewModel
class TvCompanionViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val serverDatabase: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<TvCompanionState>(TvCompanionState.Idle)
    val state: StateFlow<TvCompanionState> = _state

    private val json = Json { ignoreUnknownKeys = true }
    private var pairingServer: TvCompanionPairingServer? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    fun startPairing() {
        pairingServer?.close()

        val token = UUID.randomUUID().toString().replace("-", "")
        val manualCode = token.take(6).uppercase()
        val expiresAt = System.currentTimeMillis() + TV_COMPANION_PAIRING_DURATION_MS
        val localUrls = resolveReceiverUrls()

        if (localUrls.isEmpty()) {
            _state.value = TvCompanionState.Error("No local network address was found. Connect the TV to Wi-Fi or Ethernet and try again.")
            return
        }

        val payload =
            CompanionTvPairingPayload(
                version = 1,
                receiver_url = localUrls.first(),
                pairing_token = token,
                manual_code = manualCode,
                device_name = buildDeviceName(),
                expires_at_epoch_ms = expiresAt,
            )
        val qrContent = json.encodeToString(payload)

        pairingServer =
            TvCompanionPairingServer(
                port = TV_COMPANION_PORT,
                token = token,
                manualCode = manualCode,
                expiresAtEpochMs = expiresAt,
                deviceName = buildDeviceName(),
                json = json,
                onEnvelope = { envelope ->
                    viewModelScope.launch {
                        _state.value = TvCompanionState.Applying
                        runCatching {
                            appPreferences.setValue(appPreferences.companionUrl, envelope.companion_url)
                            appPreferences.setValue(appPreferences.companionToken, envelope.setup_token)
                            applyConfig(envelope.config)
                            appPreferences.setValue(appPreferences.lastCompanionSyncTime, System.currentTimeMillis())
                            scheduleCompanionSync()
                        }.onSuccess {
                            _state.value = TvCompanionState.Success
                        }.onFailure { error ->
                            Timber.e(error, "TV companion pairing failed")
                            _state.value =
                                TvCompanionState.Error(
                                    error.message ?: "Failed to apply companion config.",
                                )
                        }
                    }
                },
            )

        runCatching { pairingServer?.start() }
            .onFailure { error ->
                Timber.e(error, "TV companion pairing server failed to start")
                _state.value =
                    TvCompanionState.Error(
                        error.message ?: "Failed to start the local pairing receiver.",
                    )
            }
            .onSuccess {
                registerNsdService(manualCode)
                _state.value =
                    TvCompanionState.Ready(
                        payload = payload,
                        manualCode = manualCode,
                        localUrls = localUrls,
                        qrContent = qrContent,
                    )
            }
    }

    private fun registerNsdService(manualCode: String) {
        unregisterNsdService()
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = TV_COMPANION_NSD_NAME
            serviceType = TV_COMPANION_NSD_TYPE
            port = TV_COMPANION_PORT
            // Attributes require API 21+ for setAttribute; values are UTF-8 bytes.
            runCatching { setAttribute("manual_code", manualCode) }
            runCatching { setAttribute("device_name", buildDeviceName()) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Timber.w("NSD register failed: %d", errorCode)
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Timber.i("NSD registered: %s", serviceInfo?.serviceName)
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { nsdRegistrationListener = listener }
            .onFailure { Timber.w(it, "NSD registration threw") }
    }

    private fun unregisterNsdService() {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdRegistrationListener?.let {
            runCatching { nsd.unregisterService(it) }
            nsdRegistrationListener = null
        }
    }

    fun reset() {
        _state.value = TvCompanionState.Idle
    }

    override fun onCleared() {
        pairingServer?.close()
        pairingServer = null
        unregisterNsdService()
        super.onCleared()
    }

    private fun scheduleCompanionSync() {
        val request =
            PeriodicWorkRequestBuilder<CompanionSyncWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "companion_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun resolveReceiverUrls(): List<String> =
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { iface -> iface.isUp && !iface.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { address -> !address.isLoopbackAddress && address.hostAddress != null }
                    .map { address -> "http://${address.hostAddress}:$TV_COMPANION_PORT/api/v1/tv-pairing/config" }
            }
            .distinct()

    private fun buildDeviceName(): String =
        listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Fin Player TV" }

    private suspend fun applyConfig(config: CompanionConfig) =
        withContext(Dispatchers.IO) {
            applyPreferences(config.preferences)

            val importedSessions = mutableListOf<ImportedSession>()
            val importedUserPreferences = mutableMapOf<UUID, Map<String, String?>>()
            val previousCurrentServer = appPreferences.getValue(appPreferences.currentServer)

            config.servers.forEach { serverConfig ->
                val serverId = serverConfig.id
                val rawAddress = serverConfig.addresses.firstOrNull().orEmpty()
                val baseUrl =
                    if (rawAddress.isNotEmpty() && !rawAddress.endsWith("/")) "$rawAddress/" else rawAddress
                var serverSession: ImportedSession? = null
                val validUsers = mutableListOf<User>()

                serverConfig.users.forEach { companionUser ->
                    runCatching {
                        authenticateOrUseToken(companionUser, baseUrl, serverId)
                    }.getOrNull()?.let { user ->
                        validUsers.add(user)
                        importedUserPreferences[user.id] = companionUser.preferences
                        if (serverSession == null) {
                            serverSession =
                                ImportedSession(
                                    serverId = serverId,
                                    baseUrl = baseUrl,
                                    userId = user.id,
                                    accessToken = user.accessToken,
                                )
                        }
                    }
                }

                if (validUsers.isNotEmpty()) {
                    val server =
                        Server(
                            id = serverId,
                            name = serverConfig.name,
                            currentServerAddressId = null,
                            currentUserId = serverSession?.userId,
                        )
                    serverDatabase.insertServer(server)
                    val addressId = UUID.randomUUID()
                    serverDatabase.insertServerAddress(
                        ServerAddress(
                            id = addressId,
                            serverId = serverId,
                            address = baseUrl,
                        ),
                    )
                    server.currentServerAddressId = addressId
                    serverDatabase.update(server)
                    validUsers.forEach(serverDatabase::insertUser)
                    serverSession?.let(importedSessions::add)
                }
            }

            applyNetworkShares(config.networkShares)

            val activeSession =
                importedSessions.firstOrNull { it.serverId == previousCurrentServer }
                    ?: importedSessions.firstOrNull()

            activeSession?.let { session ->
                appPreferences.setValue(appPreferences.currentServer, session.serverId)
                appPreferences.setValue(appPreferences.onboardingCompleted, true)
                jellyfinApi.api.update(baseUrl = session.baseUrl, accessToken = session.accessToken)
                jellyfinApi.userId = session.userId
                importedUserPreferences[session.userId]
                    ?.filterValues { it != null }
                    ?.mapValues { it.value!! }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::applyPreferences)
            }
        }

    private suspend fun authenticateOrUseToken(
        user: CompanionUser,
        baseUrl: String,
        serverId: String,
    ): User? {
        if (user.accessToken != null) {
            runCatching {
                jellyfinApi.api.update(baseUrl = baseUrl, accessToken = user.accessToken)
                val userInfo = jellyfinApi.userApi.getCurrentUser().content
                return User(
                    id = userInfo.id,
                    name = user.username,
                    serverId = serverId,
                    accessToken = user.accessToken,
                    preferences = serializeUserPrefs(user.preferences),
                )
            }
        }

        if (user.password != null) {
            jellyfinApi.api.update(baseUrl = baseUrl, accessToken = null)
            val authResult =
                jellyfinApi.userApi.authenticateUserByName(
                    data = AuthenticateUserByName(username = user.username, pw = user.password),
                ).content

            return User(
                id = authResult.user?.id ?: UUID.randomUUID(),
                name = user.username,
                serverId = serverId,
                accessToken = authResult.accessToken,
                preferences = serializeUserPrefs(user.preferences),
            )
        }

        return null
    }

    private fun serializeUserPrefs(prefs: Map<String, String?>): String? {
        val nonNull = prefs.filterValues { it != null }.mapValues { it.value!! }
        return if (nonNull.isNotEmpty()) json.encodeToString(nonNull) else null
    }

    private fun applyPreferences(preferences: Map<String, String?>) {
        preferences.forEach { (key, value) ->
            try {
                if (!appPreferences.applyCompanionPreference(key, value)) {
                    Unit
                }
            } catch (e: Exception) {
                Timber.w(e, "TV COMPANION: Failed to apply preference $key")
            }
        }
    }

    private fun applyNetworkShares(networkShares: List<CompanionNetworkShare>) {
        networkShares.forEach { s ->
            val target: SmbConnectionTarget? = if (s.protocol.equals("smb", ignoreCase = true)) {
                SmbPathNormalizer.normalizeConnectionTarget(s.host, s.shareName)
            } else {
                null
            }
            val normalizedHost = target?.host ?: s.host
            val normalizedShareName = target?.shareName ?: s.shareName

            serverDatabase.insertNetworkShare(
                NetworkShareDto(
                    id = s.id,
                    protocol = s.protocol,
                    host = normalizedHost,
                    shareName = normalizedShareName,
                    path = s.path ?: "$normalizedHost/$normalizedShareName",
                    displayName = s.displayName,
                    username = s.username,
                    password = s.password,
                    domain = s.domain,
                    addedAtEpochMs = s.addedAtEpochMs ?: System.currentTimeMillis(),
                    lastScannedAtEpochMs = null,
                ),
            )
        }
    }
}

private class TvCompanionPairingServer(
    private val port: Int,
    private val token: String,
    private val manualCode: String,
    private val expiresAtEpochMs: Long,
    private val deviceName: String,
    private val json: Json,
    private val onEnvelope: suspend (CompanionTvPairingEnvelope) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start() {
        serverSocket = ServerSocket(port)
        acceptJob =
            scope.launch {
                while (true) {
                    val socket =
                        try {
                            serverSocket?.accept() ?: break
                        } catch (_: java.net.SocketException) {
                            // close() calls ServerSocket.close() which makes accept() throw
                            // on its blocked thread. That's our normal stop signal — exit
                            // cleanly instead of letting the uncaught exception crash the app.
                            break
                        } catch (_: java.io.IOException) {
                            break
                        }
                    scope.launch {
                        runCatching { handleClient(socket) }
                            .onFailure { Timber.w(it, "TV pairing client handler failed") }
                    }
                }
            }
    }

    fun close() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeResponse(client, 400, """{"error":"bad_request"}""")
                return
            }
            val method = parts[0]
            val path = parts[1]

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val headerParts = line.split(":", limit = 2)
                if (headerParts.size == 2) {
                    headers[headerParts[0].trim().lowercase()] = headerParts[1].trim()
                }
            }

            when {
                method == "GET" && path == "/api/v1/tv-pairing/info" -> {
                    val info =
                        CompanionTvPairingInfo(
                            version = 1,
                            manual_code = manualCode,
                            device_name = deviceName,
                            expires_at_epoch_ms = expiresAtEpochMs,
                        )
                    writeResponse(client, 200, json.encodeToString(info))
                }
                method == "POST" && path == "/api/v1/tv-pairing/config" -> {
                    val suppliedToken = headers["x-pairing-token"]?.trim().orEmpty()
                    val pairingExpired = System.currentTimeMillis() > expiresAtEpochMs
                    val tokenAccepted =
                        suppliedToken == token ||
                            suppliedToken.equals(manualCode, ignoreCase = true)
                    if (pairingExpired || !tokenAccepted) {
                        writeResponse(client, 401, """{"error":"invalid_pairing_token"}""")
                        return
                    }

                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyChars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(bodyChars, read, contentLength - read)
                        if (count <= 0) break
                        read += count
                    }
                    val body = String(bodyChars, 0, read)
                    val envelope = json.decodeFromString<CompanionTvPairingEnvelope>(body)
                    onEnvelope(envelope)
                    writeResponse(client, 200, """{"ok":true}""")
                }
                else -> writeResponse(client, 404, """{"error":"not_found"}""")
            }
        }
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        body: String,
    ) {
        val statusText =
            when (statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                else -> "Error"
            }
        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).use { writer ->
            writer.write("HTTP/1.1 $statusCode $statusText\r\n")
            writer.write("Content-Type: application/json; charset=utf-8\r\n")
            writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write(body)
            writer.flush()
        }
    }
}

@Composable
fun TvCompanionScreen(
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    viewModel: TvCompanionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is TvCompanionState.Success) {
            onRefresh()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startPairing()
    }

    // Auto-rotate the pairing code ~2s before it expires so the user never
    // sees a stale code. Re-keyed on each new Ready state so the next rotation
    // schedules itself automatically.
    LaunchedEffect(state) {
        val current = state
        if (current is TvCompanionState.Ready) {
            val msUntilExpiry = current.payload.expires_at_epoch_ms - System.currentTimeMillis()
            val delayMs = (msUntilExpiry - 2_000L).coerceAtLeast(0L)
            delay(delayMs)
            viewModel.startPairing()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TvPlaceholderScreen(
            title = "Companion Pairing",
            body = "Scan this TV QR from the companion app on your phone, or use the manual pairing code as a fallback.",
        )

        when (val current = state) {
            TvCompanionState.Idle -> Unit
            TvCompanionState.Applying -> {
                Surface(
                    colors = SurfaceDefaults.colors(containerColor = Color(0xCC131A24)),
                    shape = RoundedCornerShape(30.dp),
                ) {                    Column(
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("Applying companion configuration...", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            is TvCompanionState.Error -> {
                Surface(
                    colors = SurfaceDefaults.colors(containerColor = Color(0xCC131A24)),
                    shape = RoundedCornerShape(30.dp),
                ) {                    Column(
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Pairing failed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                        Text(current.message, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = viewModel::startPairing) { Text("Retry") }
                            OutlinedButton(onClick = onBack) { Text("Back") }
                        }
                    }
                }
            }
            TvCompanionState.Success -> {
                Surface(
                    colors = SurfaceDefaults.colors(containerColor = Color(0xCC131A24)),
                    shape = RoundedCornerShape(30.dp),
                ) {                    Column(
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Companion import complete", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Fin Player TV now has the imported server, users, preferences, and saved companion sync connection.")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { onRefresh(); onBack() }) { Text("Done") }
                            OutlinedButton(onClick = { onRefresh(); viewModel.startPairing() }) { Text("Pair Again") }
                        }
                    }
                }
            }
            is TvCompanionState.Ready -> {
                val qrBitmap = remember(current.qrContent) { generateQrBitmap(current.qrContent, 720) }
                var secondsLeft by remember(current.payload.expires_at_epoch_ms) {
                    mutableStateOf(((current.payload.expires_at_epoch_ms - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt())
                }
                LaunchedEffect(current.payload.expires_at_epoch_ms) {
                    while (secondsLeft > 0) {
                        delay(1000)
                        secondsLeft = ((current.payload.expires_at_epoch_ms - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Surface(
                        colors = SurfaceDefaults.colors(containerColor = Color(0xCC131A24)),
                        shape = RoundedCornerShape(30.dp),
                    ) {                        Box(
                            modifier = Modifier.padding(28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Companion pairing QR",
                                    modifier =
                                        Modifier
                                            .size(320.dp)
                                            .background(Color.White, RoundedCornerShape(24.dp))
                                            .padding(14.dp),
                                )
                            } else {
                                Text("QR unavailable")
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        colors = SurfaceDefaults.colors(containerColor = Color(0xCC131A24)),
                        shape = RoundedCornerShape(30.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text("Manual Pairing Code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(current.manualCode, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            Text("Receiver URL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(current.payload.receiver_url, style = MaterialTheme.typography.bodyLarge)
                            if (current.localUrls.size > 1) {
                                Text("Alternate Local URLs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                current.localUrls.drop(1).forEach { url ->
                                    Text(url, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val minutes = secondsLeft / 60
                                val seconds = secondsLeft % 60
                                val countdownText = String.format("%02d:%02d", minutes, seconds)
                                Button(onClick = viewModel::startPairing) { Text("Refresh Code ($countdownText)") }
                                OutlinedButton(onClick = onBack) { Text("Back") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun generateQrBitmap(
    content: String,
    size: Int,
): Bitmap? =
    runCatching {
        val hints =
            mapOf(
                EncodeHintType.MARGIN to 1,
            )
        val matrix =
            MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                size,
                size,
                hints,
            )
        matrix.toBitmap()
    }.getOrNull()

private fun BitMatrix.toBitmap(): Bitmap {
    val width = width
    val height = height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
