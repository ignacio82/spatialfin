package dev.spatialfin.unified.applock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.spatialfin.R

/** Two-step PIN setup / change flow. */
@Composable
fun PinSetupScreen(
    onConfirmed: (String) -> Unit,
    onCancel: () -> Unit,
    minLength: Int = PinCredentialStore.MIN_PIN_LEN,
    maxLength: Int = PinCredentialStore.MAX_PIN_LEN,
) {
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var mismatchError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun handleSubmit(pin: String) {
        val first = firstEntry
        if (first == null) {
            firstEntry = pin
            mismatchError = null
        } else if (first == pin) {
            onConfirmed(pin)
        } else {
            firstEntry = null
            mismatchError = context.getString(R.string.app_lock_pin_mismatch)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(
                    if (firstEntry == null) R.string.app_lock_pin_setup_title
                    else R.string.app_lock_pin_confirm_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_lock_pin_setup_subtitle, minLength, maxLength),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            // Re-keying: remount the pad when transitioning between the two
            // entry phases so its internal pin state resets cleanly.
            PinEntryPad(
                onSubmit = ::handleSubmit,
                busy = false,
                minLength = minLength,
                maxLength = maxLength,
            )
            if (mismatchError != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = mismatchError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.app_lock_pin_setup_cancel))
            }
        }
    }
}
