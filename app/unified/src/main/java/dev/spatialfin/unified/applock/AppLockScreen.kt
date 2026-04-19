package dev.spatialfin.unified.applock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.spatialfin.R
import kotlinx.coroutines.launch

/**
 * Full-screen blocker rendered while [AppLockManager.lockState] is LOCKED.
 * Automatically kicks off one authentication attempt on first composition;
 * the user can retry after a cancel or failure.
 */
@Composable
fun AppLockScreen(lockManager: AppLockManager) {
    val context = LocalContext.current
    val activity = remember(context) {
        var ctx: android.content.Context = context
        while (ctx is android.content.ContextWrapper && ctx !is FragmentActivity) {
            ctx = ctx.baseContext
        }
        ctx as? FragmentActivity
    }
    val scope = rememberCoroutineScope()
    var authInFlight by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun launchAuth() {
        val host = activity ?: return
        if (authInFlight) return
        authInFlight = true
        errorMessage = null
        scope.launch {
            when (val result = lockManager.authenticate(host)) {
                is AppLockManager.AuthResult.Success -> Unit
                is AppLockManager.AuthResult.Cancelled ->
                    errorMessage = context.getString(R.string.app_lock_unlock_cancelled)
                is AppLockManager.AuthResult.Failed ->
                    errorMessage = result.message.ifBlank {
                        context.getString(R.string.app_lock_unlock_failed)
                    }
            }
            authInFlight = false
        }
    }

    LaunchedEffect(Unit) { launchAuth() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp),
            )
            Text(
                text = stringResource(R.string.app_lock_unlock_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_lock_unlock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (authInFlight) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = ::launchAuth) {
                    Text(stringResource(R.string.app_lock_unlock_action))
                }
            }
            val message = errorMessage
            if (message != null && !authInFlight) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
