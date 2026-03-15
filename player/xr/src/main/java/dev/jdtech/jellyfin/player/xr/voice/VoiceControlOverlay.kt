package dev.jdtech.jellyfin.player.xr.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import dev.jdtech.jellyfin.core.R as CoreR

@Composable
fun VoiceControlOverlay(
    state: VoiceState,
    partialTranscript: String,
    modifier: Modifier = Modifier,
) {
    val isVisible = state == VoiceState.LISTENING
    
    if (isVisible) {
        Orbiter(
            position = ContentEdge.End,
            alignment = Alignment.Top,
            offset = 20.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = modifier.widthIn(max = 400.dp)
            ) {
                // Microphone icon
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF4FC3F7),
                    tonalElevation = 8.dp,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_microphone),
                            contentDescription = "Voice command",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                // Transcription or Feedback
                val textToShow = when {
                    state == VoiceState.LISTENING && partialTranscript.isNotEmpty() -> partialTranscript
                    state == VoiceState.LISTENING -> "Listening..."
                    else -> null
                }

                AnimatedVisibility(
                    visible = textToShow != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    textToShow?.let { text ->
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color.Black.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Text(
                                text = text,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
