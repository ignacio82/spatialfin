package dev.spatialfin.companion.wear.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.wear.R
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import dev.spatialfin.companion.wear.presentation.theme.WearTitleBright
import dev.spatialfin.companion.wear.presentation.theme.WearVectorIcon

/**
 * Frame 15 — no host yet.
 *
 * The app mark replaces the phone emoji in a blue circle: this is the first screen
 * a new user sees, and it should say which app is waiting.
 */
@Composable
fun WearStandaloneSetupScreen(
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06070A)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            WearDarkPrimaryContainer.copy(alpha = 0.24f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 31.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_wear),
                contentDescription = "SpatialFin",
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(9.dp))
            Text(
                text = "Waiting for SpatialFin",
                fontSize = 13.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = WearTitleBright,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Open the app on your headset, phone, or TV and setup syncs across.",
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                color = WearDarkOnSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(11.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(19.dp))
                    .background(WearDarkPrimary)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 15.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WearVectorIcon(
                    icon = WearIcons.RotateCw,
                    contentDescription = null,
                    tint = WearDarkOnPrimary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Retry",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = WearDarkOnPrimary,
                )
            }
        }
    }
}
