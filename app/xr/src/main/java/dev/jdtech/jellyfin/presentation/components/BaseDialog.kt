package dev.jdtech.jellyfin.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

/**
 * Base dialog UI component for XR. 
 * Designed to be wrapped in a SpatialDialog for 3D elevation.
 */
@Composable
fun BaseDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(contentPadding: PaddingValues) -> Unit,
) {
    BaseDialog(
        title = title,
        onDismiss = onDismiss,
        negativeButton = null,
        positiveButton = null,
        content = content
    )
}

@Composable
fun BaseDialog(
    title: String,
    onDismiss: () -> Unit,
    negativeButton: (@Composable () -> Unit)? = null,
    positiveButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.(contentPadding: PaddingValues) -> Unit,
) {
    Surface(
        modifier = Modifier
            .widthIn(min = 520.dp, max = 760.dp)
            .heightIn(max = 720.dp)
            .padding(20.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacings.large)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = MaterialTheme.spacings.large)
            )
            
            Box(modifier = Modifier.weight(1f, fill = false)) {
                Column {
                    content(PaddingValues(vertical = MaterialTheme.spacings.small))
                }
            }
            
            if (negativeButton != null || positiveButton != null) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    negativeButton?.invoke()
                    positiveButton?.invoke()
                }
            }
        }
    }
}

@Preview
@Composable
private fun BaseDialogPreview() {
    SpatialFinTheme {
        BaseDialog(title = "Dialog Title", onDismiss = {}) {
            Text(text = "Dialog content goes here.")
        }
    }
}
