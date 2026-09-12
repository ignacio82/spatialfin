package dev.jdtech.jellyfin.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The inline chrome shown next to a home row's title once the user long-presses
 * it: move the row up or down, hide it, or finish arranging.
 *
 * Shared by the XR, Beam and TV homes so a row is rearranged the same way on
 * every form factor. On TV the buttons are focusable, so the D-pad walks them.
 */
@Composable
fun HomeRowArrangeControls(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isHidden: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(99.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrangeButton(
                icon = Icons.Rounded.KeyboardArrowUp,
                description = "Move row up",
                enabled = canMoveUp,
                onClick = onMoveUp,
                focusRequester = if (canMoveUp) initialFocusRequester else null,
            )
            ArrangeButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                description = "Move row down",
                enabled = canMoveDown,
                onClick = onMoveDown,
                focusRequester = if (!canMoveUp && canMoveDown) initialFocusRequester else null,
            )
            ArrangeButton(
                icon = if (isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                description = if (isHidden) "Show row on home" else "Hide row from home",
                enabled = true,
                onClick = onToggleVisibility,
                focusRequester = if (!canMoveUp && !canMoveDown) initialFocusRequester else null,
            )
            ArrangeButton(
                icon = Icons.Rounded.Check,
                description = "Done arranging rows",
                enabled = true,
                onClick = onDone,
            )
        }
    }
}

@Composable
private fun ArrangeButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    // Deliberately NOT size-constrained: an explicit `Modifier.size()` here lands
    // outside IconButton's own minimumInteractiveComponentSize(), which clamps the
    // touch target below the 48dp minimum. That is survivable on a phone and close
    // to unusable on an XR panel driven by a hand ray, where these controls first
    // shipped too small to hit. Shrink the glyph, never the target.
    var isFocused by remember { mutableStateOf(false) }
    val focusShape = RoundedCornerShape(99.dp)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .semantics { contentDescription = description }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent,
                focusShape
            )
            .border(
                if (isFocused) 2.dp else 0.dp,
                if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                focusShape
            ),
        colors =
            IconButtonDefaults.iconButtonColors(
                contentColor = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/**
 * Marks a home row's title as the handle that opens [HomeRowArrangeControls].
 *
 * A plain tap is deliberately swallowed rather than forwarded: row titles are
 * not otherwise clickable, and letting the tap fall through to the row below
 * would make the long-press target feel unreliable.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.homeRowArrangeHandle(enabled: Boolean = true, onLongPress: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        this.combinedClickable(
            onClick = {},
            onLongClick = onLongPress,
            onLongClickLabel = "Arrange home rows",
        )
    }

/**
 * Everything a home row needs to host [HomeRowArrangeControls]: whether it is the
 * row currently being arranged, which moves are legal from its position, and the
 * callbacks that persist the result.
 *
 * Passed down to the row composables so the XR home reuses the exact same
 * interaction the Beam and TV homes expose.
 */
@androidx.compose.runtime.Immutable
data class HomeRowArrangeState(
    val isArranging: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onStartArranging: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onHide: () -> Unit,
    val onDone: () -> Unit,
)

/** Renders [HomeRowArrangeControls] for [state] when that row is being arranged. */
@Composable
fun HomeRowArrangeSlot(
    state: HomeRowArrangeState?,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
) {
    if (state == null || !state.isArranging) return
    HomeRowArrangeControls(
        canMoveUp = state.canMoveUp,
        canMoveDown = state.canMoveDown,
        isHidden = false,
        onMoveUp = state.onMoveUp,
        onMoveDown = state.onMoveDown,
        onToggleVisibility = state.onHide,
        onDone = state.onDone,
        modifier = modifier,
        initialFocusRequester = initialFocusRequester,
    )
}
