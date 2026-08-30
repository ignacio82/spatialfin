package dev.spatialfin.companion.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.wear.fcast.WearAudioReceiverService
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary

@Composable
fun WearReceiverSettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val isSinkActive by WearAudioReceiverService.isSinkActive.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Private Audio Sink",
            style = MaterialTheme.typography.titleMedium,
            color = WearDarkPrimary,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        Text(
            text = "Renders cinema audio to watch-connected earbuds in Split-A/V mode.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        FilledTonalButton(
            onClick = {
                if (isSinkActive) {
                    WearAudioReceiverService.stop(context)
                } else {
                    WearAudioReceiverService.start(context)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isSinkActive) "Sink: Enabled" else "Sink: Disabled",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSinkActive) WearDarkPrimary else Color.Gray,
                )
                Text(text = if (isSinkActive) "🟢" else "⚪")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth(0.7f),
        ) {
            Text("Done")
        }
    }
}
