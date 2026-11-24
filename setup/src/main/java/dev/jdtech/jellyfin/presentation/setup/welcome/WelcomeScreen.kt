package dev.jdtech.jellyfin.presentation.setup.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.spatialfin.presentation.theme.SpatialFinTheme

/**
 * Unified 3-choice XR source picker shown during first-run (`SessionMode.UNCONFIGURED`).
 */
@Composable
fun WelcomeScreen(
    onConnectToServer: () -> Unit,
    onSetUpFromDevice: () -> Unit,
    onActivateStandalone: () -> Unit,
) {
    val scrollState = rememberScrollState()

    RootLayout {
        Column(
            modifier =
                Modifier.fillMaxHeight()
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = SetupR.drawable.ic_beam_launcher),
                contentDescription = null,
                modifier = Modifier.width(280.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(SetupR.string.welcome),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            SourceChoiceCard(
                title = stringResource(SetupR.string.connect_to_jellyfin_server),
                body = stringResource(SetupR.string.connect_to_jellyfin_server_body),
                primary = true,
                onClick = onConnectToServer,
            )

            SourceChoiceCard(
                title = stringResource(SetupR.string.set_up_from_another_device),
                body = stringResource(SetupR.string.set_up_from_another_device_body),
                primary = false,
                onClick = onSetUpFromDevice,
            )

            SourceChoiceCard(
                title = stringResource(SetupR.string.continue_without_server),
                body = stringResource(SetupR.string.continue_without_server_body),
                primary = false,
                onClick = onActivateStandalone,
            )
        }
    }
}

@Composable
private fun SourceChoiceCard(
    title: String,
    body: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (primary) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text(text = title)
                }
            } else {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text(text = title)
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun WelcomeScreenPreview() {
    SpatialFinTheme {
        WelcomeScreen(
            onConnectToServer = {},
            onSetUpFromDevice = {},
            onActivateStandalone = {},
        )
    }
}
