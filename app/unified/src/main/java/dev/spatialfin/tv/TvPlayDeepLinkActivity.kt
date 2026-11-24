package dev.spatialfin.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import dev.jdtech.jellyfin.deeplink.PlayDeepLink
import dev.spatialfin.unified.DeviceClass
import dev.spatialfin.unified.UnifiedMainActivity
import dev.spatialfin.unified.deviceCapabilities
import timber.log.Timber

/**
 * Exported `spatialfin://play` trampoline for Android TV Watch Next and launcher deep links.
 * Validates the URI and TV form factor, strips all caller extras, and forwards only the
 * parsed URI to [UnifiedMainActivity] so App Lock and SessionMode gates always enforce policy.
 */
class TvPlayDeepLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val isTv =
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                deviceCapabilities().deviceClass == DeviceClass.TV
        val parsed = if (isTv) PlayDeepLink.parse(uri) else null
        if (parsed != null && uri != null) {
            val forward = Intent(this, UnifiedMainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, null)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { startActivity(forward) }
                .onFailure { Timber.w(it, "TvPlayDeepLinkActivity: failed to forward deep link") }
        } else {
            Timber.w("TvPlayDeepLinkActivity: rejected deep link isTv=%b uri=%s", isTv, uri)
        }
        finish()
    }
}
