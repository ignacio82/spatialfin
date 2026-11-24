package dev.jdtech.jellyfin.data.musicassistant.api

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Handles RPC request/response correlation for the Music Assistant API.
 */
@OptIn(ExperimentalAtomicApi::class)
class RpcEngine(
    private val onAuthError: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val pendingResponses =
        AtomicReference<Map<String, (Result<Answer>) -> Unit>>(emptyMap())

    // Accumulated partial results: message_id -> list of result items received so far.
    private val partialResults =
        AtomicReference<Map<String, List<JsonElement>>>(emptyMap())

    fun handleResponse(message: JsonObject): Boolean {
        val messageId = message["message_id"]?.jsonPrimitive?.contentOrNull ?: return false
        val isPartial = message["partial"]?.jsonPrimitive?.boolean == true

        if (isPartial) {
            val resultArray = message["result"]?.jsonArray
            if (resultArray == null || resultArray.isEmpty()) return true
            if (!pendingResponses.load().containsKey(messageId)) return true
            partialResults.update { current ->
                val existing = current[messageId].orEmpty()
                current + (messageId to existing + resultArray)
            }
            return true
        }

        val callback = pendingResponses.getAndUpdate { it - messageId }[messageId]
            ?: return true
        val accumulated = partialResults.getAndUpdate { it - messageId }[messageId]

        val finalMessage = if (accumulated != null) {
            val merged = accumulated.toMutableList()
            message["result"]?.jsonArray?.let(merged::addAll)
            JsonObject(message.toMutableMap().apply { put("result", JsonArray(merged)) })
        } else {
            message
        }

        val answer = Answer(finalMessage)
        if (answer.json.containsKey("error_code")) {
            Timber.e("RPC error for message $messageId: $answer")
            val errorCode = answer.json["error_code"]?.jsonPrimitive?.int
            if (errorCode == ERROR_CODE_AUTH_REQUIRED) {
                onAuthError()
            } else {
                answer.json["details"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onError)
            }
        }
        callback.invoke(Result.success(answer))
        return true
    }

    /** Register a pending request callback by message_id. */
    fun registerCallback(messageId: String, callback: (Result<Answer>) -> Unit) {
        pendingResponses.update { it + (messageId to callback) }
    }

    /** Remove a pending request callback (for cancellation on send failure). */
    fun removeCallback(messageId: String) {
        pendingResponses.update { it - messageId }
        partialResults.update { it - messageId }
    }

    /**
     * Atomically drain all pending requests and fail every drained callback with [cause]
     * so suspended RPC callers unblock immediately on disconnect instead of deadlocking.
     */
    fun clear(cause: Throwable = CancellationException("MA client disconnected")) {
        val drained = pendingResponses.getAndUpdate { emptyMap() }
        partialResults.store(emptyMap())
        for (callback in drained.values) {
            runCatching { callback.invoke(Result.failure(cause)) }
        }
    }

    private companion object {
        const val ERROR_CODE_AUTH_REQUIRED = 20
    }
}

@OptIn(ExperimentalAtomicApi::class)
private inline fun <T> AtomicReference<T>.update(transform: (T) -> T) {
    while (true) {
        val current = load()
        val updated = transform(current)
        if (compareAndSet(current, updated)) return
    }
}

@OptIn(ExperimentalAtomicApi::class)
private inline fun <T> AtomicReference<T>.getAndUpdate(transform: (T) -> T): T {
    while (true) {
        val current = load()
        val updated = transform(current)
        if (compareAndSet(current, updated)) return current
    }
}
