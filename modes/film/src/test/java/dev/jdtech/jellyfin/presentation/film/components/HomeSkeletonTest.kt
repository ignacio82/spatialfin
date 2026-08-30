package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The skeleton has no behaviour to assert, so this covers the two things that
 * could silently break it: it announces nothing, and it stays the size the
 * caller's card geometry implies.
 *
 * The height assertions are the point. The first draft sized cards with
 * `Modifier.aspectRatio`, and every card past the right edge of the row — there
 * are deliberately more than fit — got a zero width budget, at which point
 * `aspectRatio` gives up on width and matches the *height* constraint instead:
 * each one inflated to the full remaining screen height and took the row's
 * height with it. A placeholder that paints a screen-filling block is worse
 * than the gray screen it replaced, and nothing about it is visible in a code
 * review, so assert the number.
 *
 * `autoAdvance = false` is mandatory here, not stylistic — the shimmer is an
 * `infiniteRepeatable`, and `waitForIdle` (which every `onNode*` call runs)
 * never returns while the clock is driving one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeSkeletonTest {
    @get:Rule val compose = createComposeRule()

    /** One row: an 18dp title, an 8dp gap (`spacings.small`), and the card. */
    private val rowHeight = 18.dp + 8.dp

    @Test
    fun `the placeholder is not announced to a screen reader`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HomeSkeleton(modifier = Modifier.testTag("skeleton")) }

        val skeleton = compose.onNodeWithTag("skeleton", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(skeleton.config.contains(SemanticsProperties.HideFromAccessibility))
        // The placeholders carry drawing properties, but nothing readable.
        val readable = skeleton.children.filter {
            it.config.contains(SemanticsProperties.Text) ||
                it.config.contains(SemanticsProperties.ContentDescription)
        }
        assertEquals(emptyList<Any>(), readable)
    }

    @Test
    fun `a row is only as tall as one card`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HomeSkeleton(
                modifier = Modifier.testTag("skeleton"),
                cardWidth = 100.dp,
                cardAspect = 0.5f,
                rowCount = 1,
                showHero = false,
            )
        }

        // 0.5 makes a 100dp-wide card 200dp tall — however many of them overflow
        // the row, which on Robolectric's 320dp-wide screen is most of them.
        compose.onNodeWithTag("skeleton", useUnmergedTree = true)
            .assertHeightIsEqualTo(rowHeight + 200.dp)
    }

    @Test
    fun `the hero adds its own 16 by 9 band`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HomeSkeleton(
                modifier = Modifier.testTag("skeleton"),
                cardWidth = 100.dp,
                cardAspect = 0.5f,
                rowCount = 1,
                showHero = true,
            )
        }

        // The full-width hero is 320/(16/9) = 180dp, plus 16dp (`spacings.medium`)
        // of separation above the row.
        compose.onNodeWithTag("skeleton", useUnmergedTree = true)
            .assertHeightIsEqualTo(180.dp + 16.dp + rowHeight + 200.dp)
    }
}
