package dev.jdtech.jellyfin.presentation.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression cover for the arrange controls shipping too small to hit on a
 * Galaxy XR.
 *
 * They first shipped with an explicit `Modifier.size(36.dp)` on each
 * `IconButton`, which lands *outside* Material3's own
 * `minimumInteractiveComponentSize()` and clamps the touch target under the
 * 48dp minimum. That is survivable with a fingertip and effectively unusable
 * with a hand ray, so it looked fine on a phone and dead in the headset.
 * These assertions are on the *touch* bounds, not the drawn size — the glyph is
 * deliberately smaller than the target.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric tops out at SDK 36 while the project targets 37; pin it rather
// than let every test in the class fail to configure.
@Config(sdk = [36])
class HomeRowArrangeControlsTest {
    @get:Rule val compose = createComposeRule()

    private val labels =
        listOf("Move row up", "Move row down", "Hide row from home", "Done arranging rows")

    private fun setControls(
        canMoveUp: Boolean = true,
        canMoveDown: Boolean = true,
        isHidden: Boolean = false,
        onMoveUp: () -> Unit = {},
        onMoveDown: () -> Unit = {},
        onToggleVisibility: () -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        compose.setContent {
            HomeRowArrangeControls(
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                isHidden = isHidden,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onToggleVisibility = onToggleVisibility,
                onDone = onDone,
            )
        }
    }

    @Test
    fun `every control meets the 48dp minimum touch target`() {
        setControls()

        labels.forEach { label ->
            compose.onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .assertTouchWidthIsEqualTo(48.dp)
                .assertTouchHeightIsEqualTo(48.dp)
        }
    }

    @Test
    fun `move controls are disabled at the ends of the list`() {
        setControls(canMoveUp = false, canMoveDown = true)

        compose.onNodeWithContentDescription("Move row up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move row down").assertIsEnabled()
    }

    @Test
    fun `each control invokes its own callback`() {
        val fired = mutableListOf<String>()
        setControls(
            onMoveUp = { fired += "up" },
            onMoveDown = { fired += "down" },
            onToggleVisibility = { fired += "hide" },
            onDone = { fired += "done" },
        )

        compose.onNodeWithContentDescription("Move row up").performClick()
        compose.onNodeWithContentDescription("Move row down").performClick()
        compose.onNodeWithContentDescription("Hide row from home").performClick()
        compose.onNodeWithContentDescription("Done arranging rows").performClick()

        assertEquals(listOf("up", "down", "hide", "done"), fired)
    }

    @Test
    fun `a hidden row offers to show it again`() {
        setControls(isHidden = true)

        compose.onNodeWithContentDescription("Show row on home").assertIsDisplayed()
    }
}
