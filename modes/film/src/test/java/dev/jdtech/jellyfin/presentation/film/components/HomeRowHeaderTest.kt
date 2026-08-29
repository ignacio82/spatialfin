package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.models.HomeSection as HomeSectionModel
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.components.HomeRowArrangeState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cover for the arrange controls inside a real shelf header: present, sized to
 * the 48dp accessibility minimum, and reachable by a click.
 *
 * A caution for whoever touches this next. The Galaxy XR bug — controls that
 * would not respond — was "fixed" by dropping a `Modifier.size(36.dp)` and
 * loosening the header to `heightIn(min = 42.dp)`, but **neither of those is
 * measurable here**: `minimumInteractiveComponentSize()` reports 48dp touch
 * bounds either way (Compose does not clip pointer input to the parent), and
 * the drawn button is 40dp by Material3 default in both cases. Both bugs were
 * reintroduced and these assertions stayed green. So this file does *not*
 * reproduce that failure, and the real cause of it is still unproven — the fix
 * was confirmed on a debug build after the failure was seen on a staging build,
 * so the build type is an uncontrolled variable. Do not read a pass here as
 * proof the headset is fine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeRowHeaderTest {
    @get:Rule val compose = createComposeRule()

    private val section =
        HomeSectionModel(
            id = UUID.randomUUID(),
            name = UiText.DynamicString("Continue Watching"),
            items = emptyList(),
        )

    private fun arrangeState(
        isArranging: Boolean = true,
        onStartArranging: () -> Unit = {},
        onMoveUp: () -> Unit = {},
    ) = HomeRowArrangeState(
        isArranging = isArranging,
        canMoveUp = true,
        canMoveDown = true,
        onStartArranging = onStartArranging,
        onMoveUp = onMoveUp,
        onMoveDown = {},
        onHide = {},
        onDone = {},
    )

    @Test
    fun `HomeSection header does not clamp the arrange controls below 48dp`() {
        compose.setContent {
            HomeSection(
                section = section,
                itemsPadding = PaddingValues(0.dp),
                onAction = {},
                arrangeState = arrangeState(),
            )
        }

        compose.onNodeWithContentDescription("Move row up")
            .assertIsDisplayed()
            .assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun `arranging a row still shows its title`() {
        compose.setContent {
            HomeSection(
                section = section,
                itemsPadding = PaddingValues(0.dp),
                onAction = {},
                arrangeState = arrangeState(),
            )
        }

        compose.onNodeWithText("Continue Watching").assertIsDisplayed()
    }

    @Test
    fun `moving a row up reaches the callback`() {
        var moved = 0
        compose.setContent {
            HomeSection(
                section = section,
                itemsPadding = PaddingValues(0.dp),
                onAction = {},
                arrangeState = arrangeState(onMoveUp = { moved++ }),
            )
        }

        compose.onNodeWithContentDescription("Move row up").performClick()

        assertEquals(1, moved)
    }

    @Test
    fun `a row not being arranged shows no controls`() {
        compose.setContent {
            HomeSection(
                section = section,
                itemsPadding = PaddingValues(0.dp),
                onAction = {},
                arrangeState = arrangeState(isArranging = false),
            )
        }

        compose.onNodeWithContentDescription("Move row up").assertDoesNotExist()
        compose.onNodeWithText("Continue Watching").assertIsDisplayed()
    }
}
