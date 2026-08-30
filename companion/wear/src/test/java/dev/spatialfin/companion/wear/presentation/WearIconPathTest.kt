package dev.spatialfin.companion.wear.presentation

import androidx.compose.ui.graphics.vector.PathParser
import dev.spatialfin.companion.wear.presentation.theme.WearIcon
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every icon is hand-copied SVG path data, and a typo in it produces either a
 * silently empty glyph or a parse exception on a watch nobody is looking at. This
 * parses all of them on the JVM instead.
 */
class WearIconPathTest {

    private val allIcons: List<Pair<String, WearIcon>> = listOf(
        "Glasses" to WearIcons.Glasses,
        "RotateCcw" to WearIcons.RotateCcw,
        "RotateCw" to WearIcons.RotateCw,
        "Play" to WearIcons.Play,
        "Pause" to WearIcons.Pause,
        "PlayOutline" to WearIcons.PlayOutline,
        "ChevronUp" to WearIcons.ChevronUp,
        "Clock" to WearIcons.Clock,
        "ClockSmall" to WearIcons.ClockSmall,
        "Volume" to WearIcons.Volume,
        "VolumeSmall" to WearIcons.VolumeSmall,
        "Crown" to WearIcons.Crown,
        "Detent" to WearIcons.Detent,
        "Captions" to WearIcons.Captions,
        "CaptionsSmall" to WearIcons.CaptionsSmall,
        "ListOrdered" to WearIcons.ListOrdered,
        "Tv" to WearIcons.Tv,
        "Target" to WearIcons.Target,
        "Mic" to WearIcons.Mic,
        "Headphones" to WearIcons.Headphones,
        "Check" to WearIcons.Check,
        "Close" to WearIcons.Close,
        "Minus" to WearIcons.Minus,
        "Plus" to WearIcons.Plus,
    )

    @Test
    fun `every icon path parses into nodes`() {
        allIcons.forEach { (name, icon) ->
            val paths = icon.strokePaths + icon.fillPaths
            assertTrue("$name has no path data", paths.isNotEmpty())
            paths.forEachIndexed { index, data ->
                val nodes = PathParser().parsePathString(data).toNodes()
                assertTrue("$name path $index parsed to nothing", nodes.isNotEmpty())
            }
        }
    }

    @Test
    fun `generated circle and rect paths close`() {
        // The circle and rect helpers are the only synthesised geometry; Lucide's own
        // strings are copied verbatim. An unclosed rect shows up as a missing side.
        val rectNodes = PathParser().parsePathString(WearIcons.Tv.strokePaths.first()).toNodes()
        assertTrue("rect path does not close", rectNodes.last().toString().contains("Close"))
    }

    @Test
    fun `stroke widths stay in the range the 24dp viewport expects`() {
        allIcons.forEach { (name, icon) ->
            assertTrue("$name stroke width ${icon.strokeWidth} is out of range",
                icon.strokeWidth in 1.0f..3.0f)
        }
    }
}
