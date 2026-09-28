package dev.spatialfin.unified

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.spatialfin.test.SpatialFinTestApplication
import dev.spatialfin.tv.TvOverflowAction
import dev.spatialfin.tv.TvOverflowSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class TvOverflowSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dpadCenterActivatesInitiallyFocusedAction() {
        var clicks = 0
        compose.setContent {
            val inputModeManager = LocalInputModeManager.current
            SideEffect { inputModeManager.requestInputMode(InputMode.Keyboard) }
            TvOverflowSheet(
                title = "Actions",
                actions = listOf(
                    TvOverflowAction(id = "play", label = "Play now", onClick = { clicks++ }),
                ),
                onDismissRequest = {},
            )
        }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()

        compose.onNodeWithText("Play now")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
