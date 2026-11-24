package dev.jdtech.jellyfin.downloads

import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call

/**
 * Tracks live in-flight download HTTP [Call]s and [Job]s across [ResumableDownloadWorker]
 * instances and owns the shared per-process [commitMutex] that serializes download finalization
 * against App Lock `LOCKED` staging cleanup, cancellation, and server/user deletion sweeps.
 */
@Singleton
class ActiveDownloadRegistry @Inject constructor() {
    data class Handle(
        val taskId: String,
        val itemId: UUID,
        val serverId: String?,
        val userId: UUID?,
        val kind: String,
        val call: Call? = null,
        val job: Job? = null,
    )

    val commitMutex: Mutex = Mutex()

    private val handles = CopyOnWriteArraySet<Handle>()

    fun register(handle: Handle) {
        handles.add(handle)
    }

    fun unregister(handle: Handle) {
        handles.remove(handle)
    }

    suspend fun cancelByTaskId(taskId: String, timeoutMs: Long = DEFAULT_CANCEL_WAIT_MS) {
        cancelMatching(timeoutMs) { it.taskId == taskId }
    }

    suspend fun cancelByItemId(itemId: UUID, timeoutMs: Long = DEFAULT_CANCEL_WAIT_MS) {
        cancelMatching(timeoutMs) { it.itemId == itemId }
    }

    suspend fun cancelByServer(serverId: String, timeoutMs: Long = DEFAULT_CANCEL_WAIT_MS) {
        cancelMatching(timeoutMs) { it.serverId == serverId }
    }

    suspend fun cancelByUser(userId: UUID, timeoutMs: Long = DEFAULT_CANCEL_WAIT_MS) {
        cancelMatching(timeoutMs) { it.userId == userId }
    }

    suspend fun cancelPrimary(timeoutMs: Long = DEFAULT_CANCEL_WAIT_MS) {
        cancelMatching(timeoutMs) { it.kind.equals("PRIMARY", ignoreCase = true) }
    }

    suspend fun cancelAll(timeoutMs: Long = DEFAULT_CANCEL_WAIT_MS) {
        cancelMatching(timeoutMs) { true }
    }

    fun cancelAllNonBlocking() {
        val snapshot = handles.toList()
        for (handle in snapshot) {
            runCatching { handle.call?.cancel() }
            runCatching { handle.job?.cancel() }
        }
    }

    private suspend fun cancelMatching(
        timeoutMs: Long,
        predicate: (Handle) -> Boolean,
    ) {
        val matched = handles.filter(predicate)
        if (matched.isEmpty()) return
        for (handle in matched) {
            runCatching { handle.call?.cancel() }
            runCatching { handle.job?.cancel() }
        }
        val jobs = matched.mapNotNull { it.job }
        if (jobs.isEmpty()) return
        withTimeoutOrNull(timeoutMs) {
            for (job in jobs) {
                runCatching { job.join() }
            }
        }
    }

    companion object {
        const val DEFAULT_CANCEL_WAIT_MS: Long = 2_000L
    }
}
