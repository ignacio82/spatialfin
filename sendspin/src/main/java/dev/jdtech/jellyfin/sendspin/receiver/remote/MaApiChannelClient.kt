package dev.jdtech.jellyfin.sendspin.receiver.remote

import dev.jdtech.jellyfin.sendspin.receiver.MaRpcTransport
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService.Companion.TAG
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Music Assistant JSON-RPC client over the WebRTC `ma-api` data channel — the off-LAN
 * equivalent of `MusicAssistantGroupClient`'s REST `/api` calls.
 *
 * The wire protocol is identical to MA's WebSocket API (the same `{command, args, message_id}`
 * envelope SpatialFin already POSTs to `/api`):
 *  - On connect the gateway sends a `server_info` message (no `message_id`); only then is it
 *    safe to authenticate (the WebRTC gateway drops a `client/auth` sent before it forwards
 *    `server_info`). We send `{command:"auth", args:{token, device_name}}` and await its result.
 *  - Commands get a `{message_id, result}` (or `{message_id, error_code, details}`) reply.
 *  - Large results arrive in 500-item batches flagged `"partial": true`; we accumulate them
 *    and merge into the final reply (mirrors the official app's `RpcEngine`).
 *
 * [execute] blocks until the reply (or timeout), so it slots directly behind the synchronous
 * [MaRpcTransport] interface that `MusicAssistantGroupClient` already calls from background
 * coroutines. Inbound frames arrive on the WebRTC thread via [onMessage]; [send] marshals
 * outbound frames back onto it.
 */
internal class MaApiChannelClient(
    private val deviceName: String,
    private val tokenProvider: () -> String?,
    private val send: (String) -> Unit,
) {
    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var result: String? = null
        @Volatile var error: String? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val partials = ConcurrentHashMap<String, JSONArray>()
    private val readyLatch = CountDownLatch(1)

    @Volatile private var serverInfoSeen = false
    @Volatile private var authMessageId: String? = null
    @Volatile private var authed = false
    @Volatile private var closed = false

    val transport: MaRpcTransport = MaRpcTransport { command, args -> execute(command, args) }

    /** Feed an inbound `ma-api` text frame. */
    fun onMessage(text: String) {
        Timber.tag(TAG).d("ma-api << %s", text.take(140))
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val messageId = obj.optString("message_id", "").takeIf { it.isNotBlank() }
        if (messageId == null) {
            // server_info handshake (or an event we ignore). The MA server_info carries
            // server_id / schema_version and arrives first; gate auth on it.
            if (!serverInfoSeen && (obj.has("server_id") || obj.has("schema_version"))) {
                serverInfoSeen = true
                Timber.tag(TAG).i("ma-api: server_info received, authenticating")
                authenticate()
            } else {
                Timber.tag(TAG).d("ma-api: non-RPC message (event/unknown), keys=%s", obj.keys().asSequence().toList())
            }
            return
        }

        if (obj.optBoolean("partial", false)) {
            val batch = obj.optJSONArray("result") ?: return
            val acc = partials.getOrPut(messageId) { JSONArray() }
            for (i in 0 until batch.length()) acc.put(batch.get(i))
            return
        }

        // Final reply: merge any accumulated partials into result.
        val accumulated = partials.remove(messageId)
        val finalObj =
            if (accumulated != null) {
                obj.optJSONArray("result")?.let { tail ->
                    for (i in 0 until tail.length()) accumulated.put(tail.get(i))
                }
                JSONObject(obj.toString()).put("result", accumulated)
            } else {
                obj
            }

        if (messageId == authMessageId) {
            authed = !finalObj.has("error_code")
            if (authed) {
                Timber.tag(TAG).i("ma-api: authenticated")
            } else {
                Timber.tag(TAG).w("ma-api auth failed: %s", finalObj.optString("details", finalObj.optString("error")))
            }
            readyLatch.countDown()
            return
        }

        val p = pending.remove(messageId) ?: return
        if (finalObj.has("error_code")) {
            p.error = finalObj.optString("details", null)
                ?: finalObj.optString("error", "RPC error ${finalObj.optInt("error_code")}")
        } else {
            p.result = finalObj.toString()
        }
        p.latch.countDown()
    }

    private fun authenticate() {
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            // No token to present. Commands may fail with error_code 20, surfaced to the UI.
            Timber.tag(TAG).i("ma-api: no token available; skipping auth")
            readyLatch.countDown()
            return
        }
        val id = UUID.randomUUID().toString()
        authMessageId = id
        val payload = JSONObject()
            .put("command", "auth")
            .put("message_id", id)
            .put("args", JSONObject().put("token", token).put("device_name", deviceName))
        Timber.tag(TAG).i("ma-api: sending auth (token len=%d)", token.length)
        send(payload.toString())
    }

    /** Send a JSON-RPC command and block for its reply. Throws on timeout / RPC error. */
    fun execute(command: String, args: JSONObject?): String {
        if (closed) throw IOException("ma-api channel closed")
        if (!readyLatch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IOException("Music Assistant remote not ready (no server_info/auth)")
        }
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("command", command).put("message_id", id)
        if (args != null) payload.put("args", args)
        val p = Pending()
        pending[id] = p
        send(payload.toString())
        try {
            if (!p.latch.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("Music Assistant remote command timed out: $command")
            }
        } finally {
            pending.remove(id)
        }
        p.error?.let { throw IOException("Music Assistant remote command failed: $it") }
        return p.result ?: throw IOException("Music Assistant remote command returned no result")
    }

    fun close() {
        closed = true
        readyLatch.countDown()
        pending.values.forEach {
            it.error = "ma-api channel closed"
            it.latch.countDown()
        }
        pending.clear()
        partials.clear()
    }

    private companion object {
        const val READY_TIMEOUT_MS = 12_000L
        const val COMMAND_TIMEOUT_MS = 20_000L
    }
}
