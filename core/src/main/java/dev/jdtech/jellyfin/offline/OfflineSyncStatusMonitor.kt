package dev.jdtech.jellyfin.offline

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OfflineSyncStatus(
    val pendingChanges: Int = 0,
    val isRunning: Boolean = false,
    val runningChangeCount: Int = 0,
    val lastAttemptAtEpochMs: Long? = null,
    val lastSyncedChanges: Int = 0,
    val lastFailedChanges: Int = 0,
    val lastErrorMessage: String? = null,
)

@Singleton
class OfflineSyncStatusMonitor
@Inject
constructor(
    private val database: ServerDatabaseDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(OfflineSyncStatus())
    val state: StateFlow<OfflineSyncStatus> = _state.asStateFlow()

    init {
        scope.launch {
            database.observePendingUserDataSyncCount().collect { pendingChanges ->
                _state.update { it.copy(pendingChanges = pendingChanges) }
            }
        }
    }

    fun markSyncStarted(pendingChanges: Int) {
        if (pendingChanges <= 0) return
        _state.update {
            it.copy(
                isRunning = true,
                runningChangeCount = pendingChanges,
                lastErrorMessage = null,
            )
        }
    }

    fun markSyncFinished(
        syncedChanges: Int,
        failedChanges: Int,
        errorMessage: String? = null,
    ) {
        _state.update { current ->
            current.copy(
                isRunning = false,
                runningChangeCount = 0,
                lastAttemptAtEpochMs =
                    if (syncedChanges > 0 || failedChanges > 0 || !errorMessage.isNullOrBlank()) {
                        System.currentTimeMillis()
                    } else {
                        current.lastAttemptAtEpochMs
                    },
                lastSyncedChanges = syncedChanges,
                lastFailedChanges = failedChanges.coerceAtLeast(0),
                lastErrorMessage = errorMessage,
            )
        }
    }
}
