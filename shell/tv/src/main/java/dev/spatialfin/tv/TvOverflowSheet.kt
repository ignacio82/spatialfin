package dev.spatialfin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

data class TvOverflowAction(
    val id: String,
    val icon: ImageVector? = null,
    val iconContent: (@Composable () -> Unit)? = null,
    val label: String,
    val subtitle: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/** Actions own dismissal so asynchronous flows can keep their completion listeners alive. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvOverflowSheet(
    title: String,
    subtitle: String? = null,
    actions: List<TvOverflowAction>,
    onDismissRequest: () -> Unit
) {
    val firstItemFocus = remember { FocusRequester() }

    LaunchedEffect(actions) {
        if (actions.isNotEmpty()) {
            delay(50L)
            runCatching { firstItemFocus.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Scrim: tapping outside sheet dismisses, but pointerInput ensures
            // it NEVER acquires or intercepts D-pad focus on TV.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) {
                        detectTapGestures { onDismissRequest() }
                    }
            )

            // Sheet drawer on the right
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(420.dp)
                    .align(Alignment.CenterEnd)
                    .background(Color(0xFF0F1720))
                    .padding(32.dp)
                    .focusGroup()
                    .focusRestorer(firstItemFocus)
            ) {
                if (title.isNotBlank()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(24.dp))
                } else {
                    Spacer(Modifier.height(32.dp))
                }

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(actions, key = { _, action -> action.id }) { index, action ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        val shape = RoundedCornerShape(12.dp)
                        val contentColor = when {
                            action.danger -> Color.Red
                            isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> Color.White
                        }
                        val bgColor = when {
                            action.danger && isFocused -> Color.Red.copy(alpha = 0.25f)
                            action.danger -> Color.Red.copy(alpha = 0.10f)
                            isFocused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f)
                            else -> Color.White.copy(alpha = 0.05f)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier)
                                .tvFocus(isFocused, shape)
                                .clip(shape)
                                .background(bgColor, shape)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = action.onClick
                                )
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (action.iconContent != null) {
                                action.iconContent.invoke()
                            } else if (action.icon != null) {
                                Icon(
                                    action.icon,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    action.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = contentColor
                                )
                                if (action.subtitle != null) {
                                    Text(
                                        action.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (action.danger) Color.Red.copy(alpha = 0.7f) else if (isFocused) contentColor.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
