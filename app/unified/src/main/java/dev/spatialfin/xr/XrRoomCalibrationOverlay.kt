package dev.spatialfin.xr

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.xr.compose.platform.LocalSession
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.Space
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationChimeRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.LcrScreenPlane
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomProfileResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomSpeakerConfig
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import dev.jdtech.jellyfin.player.xr.WallAnchor
import dev.jdtech.jellyfin.player.xr.XrLcrScreenLockPolicy
import dev.jdtech.jellyfin.player.xr.computeHeadCenteredPose
import dev.jdtech.jellyfin.settings.domain.TheaterAvrTokenStore
import dev.spatialfin.fcast.session.RememberedReceiversStore
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class XrRoomCalibrationUiState(
    val serverId: String = "",
    val roomName: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val etag: String? = null,
    val profile: RoomProfileResponse? = null,
    val anchorUuid: String? = null,
    val anchorToMlpTransform: List<Float>? = null,
    val screenWidthM: Double = 2.667,
    val screenHeightM: Double = 1.5,
    val screenDepthM: Double = 3.0,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class XrRoomCalibrationViewModel @Inject constructor(
    private val httpClient: SendspinAvrHttpClient,
    private val tokenStore: TheaterAvrTokenStore,
    private val rememberedReceiversStore: RememberedReceiversStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(XrRoomCalibrationUiState())
    val uiState: StateFlow<XrRoomCalibrationUiState> = _uiState.asStateFlow()

    private var boundReceiver: CastReceiver? = null

    fun bind(receiver: CastReceiver) {
        boundReceiver = receiver
        _uiState.update {
            it.copy(
                serverId = receiver.id,
                roomName = receiver.name,
                loading = true,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            val saved = rememberedReceiversStore.load().firstOrNull { it.resolvedId == receiver.id }
            val endpoint = SendspinAvrHttpClient.Endpoint(receiver.host, receiver.port, receiver.useTls)
            val token = tokenStore.getToken(receiver.id)?.avrToken
            if (token.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        anchorUuid = saved?.anchorUuid,
                        anchorToMlpTransform = saved?.anchorToMlpTransform,
                        errorMessage = "Room is not paired. Pair this room before pinning XR coordinates.",
                    )
                }
                return@launch
            }
            when (val res = httpClient.getRoomProfile(endpoint, token)) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    val plane = res.value.lcrScreenPlane
                    val depthM = plane?.centerM?.getOrNull(2)?.let { kotlin.math.abs(it) }?.takeIf { it >= 0.75 } ?: 3.0
                    _uiState.update {
                        it.copy(
                            loading = false,
                            etag = res.etag,
                            profile = res.value,
                            roomName = res.value.roomName.ifBlank { receiver.name },
                            anchorUuid = saved?.anchorUuid,
                            anchorToMlpTransform = saved?.anchorToMlpTransform,
                            screenWidthM = plane?.widthM ?: 2.667,
                            screenHeightM = plane?.heightM ?: 1.5,
                            screenDepthM = depthM,
                            statusMessage = if (saved?.anchorUuid != null) {
                                "Existing XR wall anchor loaded for ${receiver.name}"
                            } else {
                                "Sit at your Main Listening Position (MLP) facing Center (FC) to anchor the front wall."
                            },
                        )
                    }
                }
                is SendspinAvrHttpClient.HttpResult.Error -> {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            anchorUuid = saved?.anchorUuid,
                            anchorToMlpTransform = saved?.anchorToMlpTransform,
                            errorMessage = "Unable to load room profile (${res.httpStatus}): ${res.message}",
                        )
                    }
                }
            }
        }
    }

    fun recordAnchoredWall(anchorUuid: String, anchorToMlpTransform: List<Float>, wallDepthM: Double?) {
        _uiState.update {
            it.copy(
                anchorUuid = anchorUuid,
                anchorToMlpTransform = anchorToMlpTransform,
                screenDepthM = wallDepthM?.coerceIn(0.75, 15.0) ?: it.screenDepthM,
                statusMessage = "Front wall anchored (${anchorUuid.take(8)}…). Tap each speaker to chime and pin.",
                errorMessage = null,
            )
        }
    }

    fun chimeSpeaker(role: String) {
        val receiver = boundReceiver ?: return
        val token = tokenStore.getToken(receiver.id)?.avrToken ?: return
        val endpoint = SendspinAvrHttpClient.Endpoint(receiver.host, receiver.port, receiver.useTls)
        viewModelScope.launch {
            when (
                val res = httpClient.playChime(
                    endpoint = endpoint,
                    bearerToken = token,
                    request = CalibrationChimeRequest(
                        role = role,
                        signal = "chime",
                        gainDbfs = -18.0,
                        durationMs = 500,
                    ),
                )
            ) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    _uiState.update { it.copy(statusMessage = "Chimed $role — look directly at it and tap Pin.") }
                }
                is SendspinAvrHttpClient.HttpResult.Error -> {
                    _uiState.update { it.copy(errorMessage = "Chime failed for $role: ${res.message}") }
                }
            }
        }
    }

    fun updateSpeakerCoordinate(role: String, coordM: List<Double>) {
        val current = _uiState.value.profile ?: return
        val updatedSpeakers = current.speakers.map { sp ->
            if (sp.role.equals(role, ignoreCase = true)) {
                sp.copy(coordM = coordM)
            } else {
                sp
            }
        }
        _uiState.update {
            it.copy(
                profile = current.copy(speakers = updatedSpeakers),
                statusMessage = String.format(
                    Locale.US,
                    "Pinned %s at (%.2f, %.2f, %.2f) m relative to MLP",
                    role,
                    coordM.getOrElse(0) { 0.0 },
                    coordM.getOrElse(1) { 0.0 },
                    coordM.getOrElse(2) { 0.0 },
                ),
                errorMessage = null,
            )
        }
    }

    fun adjustScreenSize(widthM: Double, depthM: Double) {
        val clampedW = widthM.coerceIn(1.2, 6.0)
        val clampedD = depthM.coerceIn(1.0, 12.0)
        _uiState.update {
            it.copy(
                screenWidthM = clampedW,
                screenHeightM = clampedW * 9.0 / 16.0,
                screenDepthM = clampedD,
            )
        }
    }

    fun saveRoomGeometryAndAnchor(onSaved: () -> Unit) {
        val receiver = boundReceiver ?: return
        val token = tokenStore.getToken(receiver.id)?.avrToken ?: run {
            _uiState.update { it.copy(errorMessage = "Missing room pairing credentials.") }
            return
        }
        val state = _uiState.value
        val baseProfile = state.profile ?: run {
            _uiState.update { it.copy(errorMessage = "Room profile not loaded yet.") }
            return
        }
        val endpoint = SendspinAvrHttpClient.Endpoint(receiver.host, receiver.port, receiver.useTls)
        val updatedProfile = baseProfile.copy(
            lcrScreenPlane = LcrScreenPlane(
                centerM = listOf(0.0, 0.0, -state.screenDepthM),
                widthM = state.screenWidthM,
                heightM = state.screenHeightM,
                normalVec = listOf(0.0, 0.0, 1.0),
            ),
        )
        _uiState.update { it.copy(saving = true, errorMessage = null) }
        viewModelScope.launch {
            when (val res = httpClient.putRoomProfile(endpoint, token, state.etag, updatedProfile)) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    // Save per-server_id XR anchor + 7-float T_anchor_to_mlp ONLY in main-process
                    // RememberedReceiversStore (never mutating pref_xr_player_wall_anchor_uuid).
                    rememberedReceiversStore.setRoomAnchor(
                        serverId = receiver.id,
                        anchorUuid = state.anchorUuid,
                        anchorToMlpTransform = state.anchorToMlpTransform,
                    )
                    _uiState.update {
                        it.copy(
                            saving = false,
                            etag = res.etag,
                            profile = res.value,
                            statusMessage = "Saved room profile & XR anchor for ${receiver.name}.",
                        )
                    }
                    onSaved()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> {
                    if (res.httpStatus == 409) {
                        // Reload latest ETag on optimistic-concurrency conflict.
                        val latest = httpClient.getRoomProfile(endpoint, token)
                        if (latest is SendspinAvrHttpClient.HttpResult.Success) {
                            _uiState.update {
                                it.copy(
                                    saving = false,
                                    etag = latest.etag,
                                    errorMessage = "Room profile changed on server (409 Conflict). Refreshed ETag — tap Save again.",
                                )
                            }
                            return@launch
                        }
                    }
                    _uiState.update {
                        it.copy(
                            saving = false,
                            errorMessage = "Save failed (${res.httpStatus}): ${res.message}",
                        )
                    }
                }
            }
        }
    }
}

/**
 * Main-process Galaxy XR Single-Anchor 6DoF Room Calibration overlay hosted inside
 * `UnifiedMainActivity`'s XR Full Space `Session` (no `SurfaceEntity`).
 */
@Composable
fun XrRoomCalibrationOverlay(
    receiver: CastReceiver,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: XrRoomCalibrationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val session = LocalSession.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasScenePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, WallAnchor.SCENE_UNDERSTANDING_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var anchoringInProgress by remember { mutableStateOf(false) }
    var mlpHeadPose by remember { mutableStateOf<Pose?>(null) }
    var rayPinDistanceM by remember { mutableFloatStateOf(2.8f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasScenePermission = granted
    }

    LaunchedEffect(receiver.id) {
        viewModel.bind(receiver)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "XR Room 6DoF Pinning",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${state.roomName} · Single front-wall anchor + MLP speaker coordinates",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onBack) { Text("Back") }
            }

            if (state.loading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Loading room profile…")
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            state.statusMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Step 1: Single Front-Wall Anchor + Seated MLP capture
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "1. Anchor Front Wall from Seated MLP",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Sit at your Main Listening Position facing the physical Center (FC) speaker (-Z), then anchor the front wall.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.anchorUuid != null) {
                        Text(
                            text = "Anchor UUID: ${state.anchorUuid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = session != null && !anchoringInProgress,
                            onClick = {
                                if (!hasScenePermission) {
                                    permissionLauncher.launch(WallAnchor.SCENE_UNDERSTANDING_PERMISSION)
                                    return@Button
                                }
                                val activeSession = session ?: return@Button
                                anchoringInProgress = true
                                scope.launch {
                                    try {
                                        val head = computeHeadCenteredPose(activeSession)
                                        if (head == null) {
                                            Timber.w("XR_ROOM_CAL: head pose unavailable")
                                            return@launch
                                        }
                                        mlpHeadPose = head
                                        if (!WallAnchor.enableSceneUnderstanding(activeSession)) return@launch
                                        val anchor = WallAnchor.findWallInFront(activeSession, WallAnchor.ANCHOR_WAIT_MS)
                                            ?: return@launch
                                        val anchorState = WallAnchor.awaitAnchored(anchor, 5_000L)
                                        if (anchorState == AnchorSpace.State.ANCHORED) {
                                            val anchorPose = runCatching { anchor.getPose(Space.ACTIVITY) }.getOrNull()
                                            val persistedUuid = WallAnchor.persist(anchor)
                                            if (anchorPose != null && persistedUuid != null) {
                                                val transform = XrLcrScreenLockPolicy.computeGravityAnchorToMlpTransform(
                                                    anchorActivityPose = anchorPose,
                                                    mlpHeadPose = head,
                                                )
                                                val dx = anchorPose.translation.x - head.translation.x
                                                val dz = anchorPose.translation.z - head.translation.z
                                                val wallDist = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
                                                rayPinDistanceM = wallDist.toFloat().coerceIn(1.2f, 8.0f)
                                                viewModel.recordAnchoredWall(
                                                    anchorUuid = persistedUuid,
                                                    anchorToMlpTransform = transform,
                                                    wallDepthM = wallDist,
                                                )
                                            }
                                        }
                                        runCatching { anchor.parent = null }
                                    } finally {
                                        anchoringInProgress = false
                                    }
                                }
                            },
                        ) {
                            Text(
                                when {
                                    anchoringInProgress -> "Finding Front Wall…"
                                    !hasScenePermission -> "Grant Scene Permission"
                                    state.anchorUuid != null -> "Re-Anchor Front Wall"
                                    else -> "Anchor Front Wall"
                                },
                            )
                        }
                    }
                }
            }

            // Step 2: Tap-to-chime speaker localization
            val speakers: List<RoomSpeakerConfig> = state.profile?.speakers.orEmpty()
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "2. Tap-to-Chime Speaker Localization",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Tap Chime to identify each physical speaker, look directly at it from MLP, and tap Pin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Pin distance:", style = MaterialTheme.typography.labelMedium)
                        listOf(1.8f, 2.5f, 3.0f, 3.8f, 4.5f).forEach { dist ->
                            FilterChip(
                                selected = kotlin.math.abs(rayPinDistanceM - dist) < 0.15f,
                                onClick = { rayPinDistanceM = dist },
                                label = { Text(String.format(Locale.US, "%.1fm", dist)) },
                            )
                        }
                    }
                    HorizontalDivider()
                    if (speakers.isEmpty()) {
                        Text(
                            text = "No speakers configured in room profile yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        speakers.forEach { sp ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${sp.role} · ${sp.clientId}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "MLP (%.2f, %.2f, %.2f) m",
                                            sp.coordM.getOrElse(0) { 0.0 },
                                            sp.coordM.getOrElse(1) { 0.0 },
                                            sp.coordM.getOrElse(2) { 0.0 },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { viewModel.chimeSpeaker(sp.role) }) {
                                        Text("Chime")
                                    }
                                    Button(
                                        enabled = session != null,
                                        onClick = {
                                            val activeSession = session ?: return@Button
                                            val curHead = computeHeadCenteredPose(activeSession) ?: return@Button
                                            val refMlp = mlpHeadPose ?: curHead.also { mlpHeadPose = it }
                                            val gazeForward = curHead.rotation * Vector3(0f, 0f, -1f)
                                            val speakerPos = Vector3(
                                                curHead.translation.x + gazeForward.x * rayPinDistanceM,
                                                curHead.translation.y + gazeForward.y * rayPinDistanceM,
                                                curHead.translation.z + gazeForward.z * rayPinDistanceM,
                                            )
                                            val coordM = XrLcrScreenLockPolicy.computeMlpRelativeSpeakerCoord(
                                                mlpHeadPose = refMlp,
                                                speakerActivityPos = speakerPos,
                                            )
                                            viewModel.updateSpeakerCoordinate(sp.role, coordM)
                                        },
                                    ) {
                                        Text("Pin")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Step 3: Physical LCR Screen Plane & Save
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "3. LCR Cinema Screen Plane & Save",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Flat LCR screen: %.2f m × %.2f m at %.2f m depth (center aligned to FC)",
                            state.screenWidthM,
                            state.screenHeightM,
                            state.screenDepthM,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.adjustScreenSize(state.screenWidthM - 0.25, state.screenDepthM) }) {
                            Text("Narrower")
                        }
                        OutlinedButton(onClick = { viewModel.adjustScreenSize(state.screenWidthM + 0.25, state.screenDepthM) }) {
                            Text("Wider")
                        }
                        OutlinedButton(onClick = { viewModel.adjustScreenSize(state.screenWidthM, state.screenDepthM - 0.25) }) {
                            Text("Closer")
                        }
                        OutlinedButton(onClick = { viewModel.adjustScreenSize(state.screenWidthM, state.screenDepthM + 0.25) }) {
                            Text("Farther")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        enabled = !state.saving && state.profile != null,
                        onClick = { viewModel.saveRoomGeometryAndAnchor(onSaved = onBack) },
                    ) {
                        Text(if (state.saving) "Saving…" else "Save Room Profile & XR Anchor")
                    }
                }
            }
        }
    }
}
