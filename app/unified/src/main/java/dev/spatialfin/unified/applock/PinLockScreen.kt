package dev.spatialfin.unified.applock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spatialfin.R

@Composable
fun PinLockScreen(
    onSubmit: (String) -> Unit,
    errorMessage: String?,
    busy: Boolean,
    remainingAttempts: Int?,
    wipeOnFail: Boolean,
    onWipeRequested: () -> Unit,
    minLength: Int = PinCredentialStore.MIN_PIN_LEN,
    maxLength: Int = PinCredentialStore.MAX_PIN_LEN,
) {
    var showWipeDialog by remember { mutableStateOf(false) }

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
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                text = stringResource(R.string.app_lock_pin_prompt_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PinEntryPad(
                onSubmit = onSubmit,
                busy = busy,
                minLength = minLength,
                maxLength = maxLength,
            )
            Spacer(Modifier.height(16.dp))
            when {
                busy -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                errorMessage != null -> Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            if (remainingAttempts != null && !busy && errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.app_lock_pin_attempts_remaining,
                        remainingAttempts,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { showWipeDialog = true }) {
                Text(stringResource(R.string.app_lock_pin_forgot))
            }
        }
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text(stringResource(R.string.app_lock_pin_wipe_title)) },
            text = {
                Text(
                    if (wipeOnFail) stringResource(R.string.app_lock_pin_wipe_body_on)
                    else stringResource(R.string.app_lock_pin_wipe_body_off)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showWipeDialog = false
                    onWipeRequested()
                }) {
                    Text(stringResource(R.string.app_lock_pin_wipe_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) {
                    Text(stringResource(R.string.app_lock_pin_wipe_cancel))
                }
            },
        )
    }
}

/** PIN entry widget: dot indicators + numeric keypad + submit. No chrome. */
@Composable
fun PinEntryPad(
    onSubmit: (String) -> Unit,
    busy: Boolean,
    minLength: Int = PinCredentialStore.MIN_PIN_LEN,
    maxLength: Int = PinCredentialStore.MAX_PIN_LEN,
) {
    var pin by remember { mutableStateOf("") }

    fun append(digit: Char) {
        if (pin.length < maxLength && !busy) pin += digit
    }
    fun backspace() {
        if (pin.isNotEmpty() && !busy) pin = pin.dropLast(1)
    }
    fun submit() {
        if (pin.length < minLength || busy) return
        val attempt = pin
        pin = ""
        onSubmit(attempt)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PinDots(filled = pin.length, total = maxLength)
        Spacer(Modifier.height(24.dp))
        NumericKeypad(
            onDigit = ::append,
            onBackspace = ::backspace,
            onSubmit = ::submit,
            submitEnabled = pin.length >= minLength && !busy,
        )
    }
}

@Composable
private fun PinDots(filled: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(total) { i ->
            val on = i < filled
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
            )
        }
    }
}

@Composable
private fun NumericKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { label ->
                    KeypadButton(label = label, onClick = { onDigit(label[0]) })
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(72.dp))
            KeypadButton(label = "0", onClick = { onDigit('0') })
            FilledTonalIconButton(
                onClick = onBackspace,
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Backspace,
                    contentDescription = null,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onSubmit,
            enabled = submitEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.app_lock_pin_submit))
        }
    }
}

@Composable
private fun KeypadButton(label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
    ) {
        Text(
            text = label,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
