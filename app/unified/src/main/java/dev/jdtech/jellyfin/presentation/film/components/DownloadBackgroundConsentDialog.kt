package dev.jdtech.jellyfin.presentation.film.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.jdtech.jellyfin.presentation.components.XrConfirmDialog

private const val PROMPTED_KEY = "pref_downloads_batt_opt_prompted"

/**
 * Renders a one-shot opt-in dialog after the first bulk download is queued,
 * asking the user to whitelist SpatialFin from battery optimizations so
 * downloads keep running when the headset is removed (Samsung's wear-detection
 * layer is more aggressive than vanilla Android about pausing foreground
 * services). Shown at most once — the choice is recorded in default
 * SharedPreferences. Callers gate visibility with [show].
 */
@Composable
fun DownloadBackgroundConsentDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        val prefs = defaultPrefs(context)
        if (prefs.getBoolean(PROMPTED_KEY, false)) return@LaunchedEffect
        if (isIgnoringBatteryOptimizations(context)) return@LaunchedEffect
        visible = true
    }

    if (!visible) return
    XrConfirmDialog(
        title = "Keep downloads running",
        message =
            "Allow SpatialFin to ignore battery optimization so downloads keep running while the headset is off. The system Settings screen will open.",
        confirmLabel = "Open settings",
        dismissLabel = "Not now",
        onConfirm = {
            markPrompted(context)
            visible = false
            launchBatterySettings(context)
            onDismiss()
        },
        onDismiss = {
            markPrompted(context)
            visible = false
            onDismiss()
        },
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun markPrompted(context: Context) {
    defaultPrefs(context)
        .edit()
        .putBoolean(PROMPTED_KEY, true)
        .apply()
}

private fun defaultPrefs(context: Context) =
    context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

private fun launchBatterySettings(context: Context) {
    val pkgUri = Uri.fromParts("package", context.packageName, null)
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }
        .onFailure {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(fallback) }
        }
}
