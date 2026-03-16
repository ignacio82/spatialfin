package dev.jdtech.jellyfin.presentation.setup.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.welcome.WelcomeAction

@Composable
fun WelcomeScreen(onContinueClick: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    WelcomeScreenLayout(
        onAction = { action ->
            when (action) {
                is WelcomeAction.OnContinueClick -> onContinueClick()
                is WelcomeAction.OnLearnMoreClick -> {
                    uriHandler.openUri("https://jellyfin.org/")
                }
            }
        }
    )
}

@Composable
private fun WelcomeScreenLayout(onAction: (WelcomeAction) -> Unit) {
    RootLayout(padding = PaddingValues(horizontal = 24.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.align(Alignment.Center)
                    .widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Image(
                painter = painterResource(id = CoreR.drawable.ic_banner),
                contentDescription = null,
                modifier = Modifier.width(320.dp),
            )
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = stringResource(SetupR.string.welcome),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(SetupR.string.welcome_text),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(40.dp))
            Column(modifier = Modifier.widthIn(max = 560.dp)) {
                OutlinedButton(
                    onClick = { onAction(WelcomeAction.OnLearnMoreClick) },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
                ) {
                    Text(
                        text = stringResource(SetupR.string.welcome_btn_learn_more),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onAction(WelcomeAction.OnContinueClick) },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
                ) {
                    Text(
                        text = stringResource(SetupR.string.welcome_btn_continue),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun WelcomeScreenLayoutPreview() {
    SpatialFinTheme { WelcomeScreenLayout(onAction = {}) }
}
