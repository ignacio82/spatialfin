package dev.jdtech.jellyfin.presentation.shell

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.jdtech.jellyfin.settings.domain.AppPreferences

/*
 * Seams that let the form-factor shell modules (`:shell:tv`, `:shell:beam`)
 * reach a couple of app-level host subsystems that stay in `:app:unified`
 * without an upward shell -> app dependency. The app supplies the
 * implementations via `CompositionLocalProvider` where it mounts each shell
 * (UnifiedMainActivity), and the shells consume them through `.current`.
 */

/**
 * The network-remote-control mini player overlay (`RemoteControlMiniPlayerHost`,
 * `dev.spatialfin.unified`). It depends on app-level remote-control session
 * state, so it stays in `:app:unified`; shells render it through this seam.
 * Null when no host is provided (the overlay simply isn't shown).
 */
val LocalRemoteControlMiniPlayerHost =
    compositionLocalOf<(@Composable (Modifier) -> Unit)?> { null }

/**
 * Reads + applies the FCast/SendSpin cast-receiver configuration for the cast
 * settings card. The backing wiring (`FCastReceiverWiring`) launches the
 * theater-only `XrFCastInboundPlayerActivity`, so it's `:player:xr`-coupled and
 * stays in `:app:unified`; the shell settings hubs reach the pure
 * pref-derived values + the restart-on-apply action through this controller.
 */
interface FCastReceiverController {
    fun isReceiverEnabled(prefs: AppPreferences): Boolean
    fun resolveDisplayName(prefs: AppPreferences): String
    fun applyReceiverConfig(context: Context, prefs: AppPreferences)
}

val LocalFCastReceiverController =
    compositionLocalOf<FCastReceiverController?> { null }
