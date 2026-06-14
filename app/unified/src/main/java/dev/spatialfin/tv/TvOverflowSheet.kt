package dev.spatialfin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

data class TvOverflowAction(
    val id: String,
    val icon: ImageVector? = null,
    val iconContent: (@Composable () -> Unit)? = null,
    val label: String,
    val subtitle: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvOverflowSheet(
    title: String,
    subtitle: String? = null,
    actions: List<TvOverflowAction>,
    onDismissRequest: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(420.dp)
                    .background(Color(0xFF0F1720))
                    .padding(32.dp)
            ) {
                if (title.isNotBlank()) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.7f))
                    Spacer(Modifier.height(24.dp))
                } else {
                    Spacer(Modifier.height(32.dp))
                }
                
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(actions, key = { it.id }) { action ->
                        var isFocused by remember { mutableStateOf(false) }
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                                .tvFocus(isFocused, shape)
                                .clickable { action.onClick() }
                                .background(if (action.danger) Color.Red.copy(0.1f) else Color.White.copy(0.05f), shape)
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (action.iconContent != null) {
                                action.iconContent.invoke()
                            } else if (action.icon != null) {
                                Icon(action.icon, contentDescription = null, tint = if (action.danger) Color.Red else Color.White, modifier = Modifier.size(24.dp))
                            }
                            Column {
                                Text(action.label, style = MaterialTheme.typography.titleMedium, color = if (action.danger) Color.Red else Color.White)
                                if (action.subtitle != null) {
                                    Text(action.subtitle, style = MaterialTheme.typography.bodySmall, color = if (action.danger) Color.Red.copy(0.7f) else Color.White.copy(0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
