package dev.spatialfin.companion.wear.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.protocol.WearTvPairingRequest
import dev.spatialfin.companion.wear.presentation.theme.WearDarkError
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOutline
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import dev.spatialfin.companion.wear.presentation.theme.WearRejectContainer
import dev.spatialfin.companion.wear.presentation.theme.WearTitleBright
import dev.spatialfin.companion.wear.presentation.theme.WearVectorIcon
import kotlinx.coroutines.delay

/**
 * Frame 14 — approve a TV pairing.
 *
 * The countdown becomes the full bezel ring so it reads at a glance rather than as
 * a number you have to find. The manual code gets mono type and real tracking,
 * because its whole job is to be read aloud to someone standing at the TV.
 */
@Composable
fun WearTvPairingDialog(
    request: WearTvPairingRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    var remainingSeconds by remember(request) {
        mutableLongStateOf(((request.expiresAtEpochMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
    }
    // Captured once so the ring measures against the window the request actually
    // opened with, not against whatever is left when this screen first composes.
    val totalSeconds = remember(request) { remainingSeconds.coerceAtLeast(1L) }

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
            .background(Color(0xFF06070A)),
    ) {
        ArcCountdownRing(fraction = remainingSeconds.toFloat() / totalSeconds)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 30.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(WearDarkSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    WearVectorIcon(
                        icon = WearIcons.Tv,
                        contentDescription = null,
                        tint = WearDarkPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "PAIR DEVICE",
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.08.em,
                    color = WearDarkOutline,
                )
                Text(
                    text = request.deviceName,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WearTitleBright,
                    textAlign = TextAlign.Center,
                )
                if (request.manualCode.isNotBlank()) {
                    Text(
                        text = request.manualCode,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.22.em,
                        color = WearDarkPrimary,
                    )
                }
                Text(
                    text = "Expires in ${remainingSeconds}s",
                    fontSize = 8.5.sp,
                    color = WearDarkOutline,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PairingButton(
                    label = "Reject",
                    icon = WearIcons.Close,
                    container = WearRejectContainer,
                    content = WearDarkError,
                    border = WearDarkError.copy(alpha = 0.28f),
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                )
                PairingButton(
                    label = "Approve",
                    icon = WearIcons.Check,
                    container = WearDarkPrimary,
                    content = WearDarkOnPrimary,
                    border = Color.Transparent,
                    onClick = onApprove,
                    modifier = Modifier.weight(1.35f),
                )
            }
        }
    }
}

@Composable
private fun PairingButton(
    label: String,
    icon: dev.spatialfin.companion.wear.presentation.theme.WearIcon,
    container: Color,
    content: Color,
    border: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(container)
            .border(1.dp, border, RoundedCornerShape(21.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WearVectorIcon(
            icon = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}
