package dev.jdtech.jellyfin.plugins.bridge

import android.util.Base64
import timber.log.Timber
import java.util.UUID

class RealUtilitiesBridge : UtilitiesBridge {

    override fun uuid(): String {
        return UUID.randomUUID().toString()
    }

    override fun base64Encode(data: String): String {
        return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
    }

    override fun base64EncodeBytes(data: IntArray): String {
        val bytes = ByteArray(data.size) { data[it].toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override fun base64Decode(data: String): String {
        return try {
            String(Base64.decode(data, Base64.DEFAULT), Charsets.ISO_8859_1)
        } catch (e: Exception) {
            Timber.e(e, "Base64 decode failed")
            ""
        }
    }

    override fun md5String(data: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val bytes = md.digest(data.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun log(message: String) {
        Timber.tag("PluginJS").d(message)
    }
}
