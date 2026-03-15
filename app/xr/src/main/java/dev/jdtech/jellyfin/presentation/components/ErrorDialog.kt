package dev.jdtech.jellyfin.presentation.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import androidx.xr.compose.spatial.SpatialDialog

@Composable
fun ErrorDialog(exception: Throwable, onDismissRequest: () -> Unit) {
    val context = LocalContext.current

    SpatialDialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(240.dp, max = 720.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column {
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
                Text(
                    text = exception.message ?: stringResource(CoreR.string.unknown_error),
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacings.large),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
                HorizontalDivider()
                Column(
                    modifier =
                        Modifier.weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .verticalScroll(rememberScrollState())
                            .padding(MaterialTheme.spacings.medium)
                ) {
                    Text(
                        text = exception.stackTraceToString(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = MaterialTheme.spacings.medium,
                                top = MaterialTheme.spacings.extraSmall,
                                end = MaterialTheme.spacings.medium,
                                bottom = MaterialTheme.spacings.small,
                            ),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val sendIntent =
                                Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "${exception.message}\n ${exception.stackTraceToString()}",
                                    )
                                    type = "text/plain"
                                }
                            val shareIntent = Intent.createChooser(sendIntent, null)
                            context.startActivity(shareIntent)
                        }
                    ) {
                        Text(stringResource(CoreR.string.share), style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { onDismissRequest() }) {
                        Text(stringResource(CoreR.string.close), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun ErrorDialogPreview() {
    SpatialFinTheme {
        ErrorDialog(exception = Exception("Error loading data"), onDismissRequest = {})
    }
}
