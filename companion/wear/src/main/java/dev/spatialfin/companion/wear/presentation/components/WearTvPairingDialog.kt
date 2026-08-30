package dev.spatialfin.companion.wear.presentation.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.protocol.WearTvPairingRequest
import dev.spatialfin.companion.wear.presentation.theme.WearDarkError
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import kotlinx.coroutines.delay

@Composable
fun WearTvPairingDialog(
    request: WearTvPairingRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    var remainingSeconds by remember(request) {
        mutableLongStateOf(((request.expiresAtEpochMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
    }

    LaunchedEffect(request) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds = ((request.expiresAtEpochMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (remainingSeconds <= 0) {
                onReject()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Pair New Device",
                style = MaterialTheme.typography.titleMedium,
                color = WearDarkPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = request.deviceName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            if (request.manualCode.isNotBlank()) {
                Text(
                    text = "Code: ${request.manualCode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WearDarkPrimary,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            Text(
                text = "Expires in ${remainingSeconds}s",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalButton(
                    onClick = onReject,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF2A1515),
                        contentColor = WearDarkError,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reject")
                }

                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Approve")
                }
            }
        }
    }
}
