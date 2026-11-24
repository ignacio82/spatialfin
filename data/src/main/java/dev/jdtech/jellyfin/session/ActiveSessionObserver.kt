package dev.jdtech.jellyfin.session

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow read-only session snapshot seam in `:data` so `:player:local`, `:player:xr`,
 * `:fcast:session-ui`, and other modules outside `:core` can observe active session state
 * and await cold-start initialization without taking a compile dependency on `:core`.
 *
 * Implemented by `:core` `SessionActivationCoordinator` and bound in `:core` `SessionModule`.
 */
data class ActiveSessionSnapshot(
    val isLoading: Boolean = true,
    val isAuthenticated: Boolean = false,
    val serverId: String? = null,
    val userId: UUID? = null,
    val sessionEpoch: Long = 0L,
    val serverAddress: String? = null,
)

interface ActiveSessionObserver {
    val activeSessionState: StateFlow<ActiveSessionSnapshot>

    suspend fun ensureInitialized(): ActiveSessionSnapshot

    object NoOp : ActiveSessionObserver {
        private val ready = ActiveSessionSnapshot(isLoading = false)
        override val activeSessionState: StateFlow<ActiveSessionSnapshot> = MutableStateFlow(ready)
        override suspend fun ensureInitialized(): ActiveSessionSnapshot = ready
    }
}
