package dev.jdtech.jellyfin.player.xr

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR

/**
 * Glass dialog language from the XR design kit (`ui_kits/xr` — `XrSheet` /
 * `XrOptionRow`): a centered translucent card with a title + subtitle header
 * and a circular close button, holding rows of circular-icon-chip options with
 * a cyan check on the active one. Shared by the player's Quality / Audio /
 * Subtitle / Speed pickers so they read as one consistent surface instead of
 * stock Material radio dialogs.
 */

private val SheetGlass = Color(0xFF14161B)
private val SheetBorder = Color.White.copy(alpha = 0.12f)

@Composable
internal fun XrGlassSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: androidx.compose.ui.unit.Dp = 560.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.width(width),
        shape = RoundedCornerShape(28.dp),
        color = SheetGlass.copy(alpha = 0.97f),
        tonalElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, SheetBorder, RoundedCornerShape(28.dp))
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(64.dp)) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(percent = 50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_x),
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
            content()
        }
    }
}

/**
 * One selectable row in an [XrGlassSheet]: an optional circular icon chip, a
 * bold title with an optional muted subtitle, and a cyan check when selected.
 */
@Composable
internal fun XrOptionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    selected: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        if (selected) OrbiterAccent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(percent = 50),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = if (selected) OrbiterAccent else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(34.dp),
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_check),
                contentDescription = "Selected",
                tint = OrbiterAccent,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}
