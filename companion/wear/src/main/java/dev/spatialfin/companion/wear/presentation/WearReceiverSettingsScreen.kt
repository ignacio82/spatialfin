package dev.spatialfin.companion.wear.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.scrollTransform
import dev.spatialfin.companion.wear.fcast.WearAudioReceiverService
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOutline
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceContainer
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import dev.spatialfin.companion.wear.presentation.theme.WearMint
import dev.spatialfin.companion.wear.presentation.theme.WearOnMint
import dev.spatialfin.companion.wear.presentation.theme.WearTitleBright
import dev.spatialfin.companion.wear.presentation.theme.WearVectorIcon

/**
 * Frame 13 — the private audio sink.
 *
 * A real switch, not a button labelled "Sink: Enabled 🟢". The old control was a
 * button whose label described a state, which meant the affordance and the status
 * were the same pixel and neither read clearly.
 *
 * Uses [TransformingLazyColumn] so that with larger accessibility font sizes or on
 * smaller watch displays the controls and "Done" button never get cut off by screen edges.
 */
@Composable
fun WearReceiverSettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val isSinkActive by WearAudioReceiverService.isSinkActive.collectAsState()
    val listState = rememberTransformingLazyColumnState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06070A)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            WearMint.copy(alpha = if (isSinkActive) 0.14f else 0.05f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .scrollTransform(this)
                        .padding(horizontal = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(WearMint.copy(alpha = 0.14f))
                            .border(1.dp, WearMint.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        WearVectorIcon(
                            icon = WearIcons.Headphones,
                            contentDescription = null,
                            tint = WearMint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Private audio",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WearTitleBright,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Cinema audio plays through your watch earbuds while video stays " +
                            "on the headset.",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = WearDarkOnSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item {
                SinkSwitchRow(
                    active = isSinkActive,
                    onToggle = {
                        if (isSinkActive) {
                            WearAudioReceiverService.stop(context)
                        } else {
                            WearAudioReceiverService.start(context)
                        }
                    },
                    modifier = Modifier.scrollTransform(this),
                )
            }

            item {
                Box(
                    modifier = Modifier
                        .scrollTransform(this)
                        .padding(top = 4.dp)
                        .size(width = 84.dp, height = 34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(WearDarkPrimary)
                        .clickable(onClick = onNavigateBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Done",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = WearDarkOnPrimary,
                    )
                }
            }
        }

        ScrollIndicator(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun SinkSwitchRow(
    active: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor by animateColorAsState(
        targetValue = if (active) WearMint else WearDarkOutline.copy(alpha = 0.4f),
        label = "sink_track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (active) 20.dp else 0.dp,
        label = "sink_thumb",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(WearDarkSurfaceContainer)
            .border(
                width = 1.dp,
                color = if (active) WearMint.copy(alpha = 0.28f) else Color.Transparent,
                shape = RoundedCornerShape(23.dp),
            )
            .clickable(onClick = onToggle)
            .padding(start = 13.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (active) "Streaming" else "Off",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (active) WearMint else WearDarkOnSurfaceVariant,
            )
        }

        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(trackColor)
                .padding(3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (active) WearOnMint else WearDarkSurfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (active) {
                    WearVectorIcon(
                        icon = WearIcons.Check,
                        contentDescription = null,
                        tint = WearMint,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}
