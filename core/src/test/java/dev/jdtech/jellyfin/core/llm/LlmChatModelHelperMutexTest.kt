package dev.jdtech.jellyfin.core.llm

import com.google.ai.edge.litertlm.Engine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression guard for the P0: [LlmChatModelHelper] acquires a process-wide
 * `inferenceMutex` before creating the LiteRT conversation. If
 * `engine.createConversation(...)` throws (GPU OOM, engine closed mid-call) the
 * mutex MUST be released, or every subsequent voice command / chat query
 * deadlocks until the app restarts.
 *
 * We drive the failing path twice: if the first call leaked the lock, the
 * second `inferenceMutex.lock()` never returns and `withTimeout` trips with a
 * [TimeoutCancellationException] instead of the thrown engine error.
 */
class LlmChatModelHelperMutexTest {
    @Test
    fun `mutex is released when createConversation throws so the next call still runs`() = runTest {
        val engine = mockk<Engine>()
        every { engine.createConversation(any()) } throws RuntimeException("GPU OOM")
        val instance = LlmModelInstance(engine = engine, backendName = "GPU")

        repeat(2) { attempt ->
            val error = runCatching {
                withTimeout(2_000) {
                    LlmChatModelHelper.runInference(instance, prompt = "hello")
                }
            }.exceptionOrNull()

            assertNotNull("attempt $attempt should surface the engine failure", error)
            assertFalse(
                "attempt $attempt deadlocked — the inferenceMutex was not released on throw",
                error is TimeoutCancellationException,
            )
        }
    }
}
