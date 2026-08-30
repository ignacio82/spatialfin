package dev.spatialfin.companion.wear.presentation.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The Wear icon set, drawn from Lucide geometry on a 24x24 viewport.
 *
 * Why not [androidx.compose.material.icons] or an `ImageVector`: the redesign is
 * specified in Lucide, whose stroke weights and terminals differ visibly from
 * Material's filled glyphs at 24-30dp on a watch — and `material-icons-extended`
 * would drag a multi-megabyte artifact into a companion APK to use fifteen
 * glyphs. Path strings are copied verbatim from the design source, so the only
 * transcription risk is a typo the render makes obvious.
 *
 * Everything is stroked except [Play] and [Pause], which are solid by design.
 */
@Immutable
data class WearIcon(
    val strokePaths: List<String> = emptyList(),
    val fillPaths: List<String> = emptyList(),
    val strokeWidth: Float = 2f,
)

/** SVG `<circle>` as path data — Lucide leans on circles, path parsing does not. */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r},$cy a$r,$r 0 1,0 ${r * 2},0 a$r,$r 0 1,0 ${-r * 2},0"

/** SVG `<rect rx>` as path data. */
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
    val ix = w - 2 * r
    val iy = h - 2 * r
    return "M${x + r},$y h$ix a$r,$r 0 0 1 $r,$r v$iy a$r,$r 0 0 1 ${-r},$r " +
        "h${-ix} a$r,$r 0 0 1 ${-r},${-r} v${-iy} a$r,$r 0 0 1 $r,${-r} z"
}

object WearIcons {

    /** Headset / target device. Lucide `glasses`. */
    val Glasses = WearIcon(
        strokePaths = listOf(
            circle(6f, 15f, 4f),
            circle(18f, 15f, 4f),
            "M14 15a2 2 0 0 0-2-2 2 2 0 0 0-2 2",
            "M2.5 13 5 7c.7-1.3 1.4-2 3-2",
            "M21.5 13 19 7c-.7-1.3-1.5-2-3-2",
        ),
    )

    /** Seek back. Lucide `rotate-ccw`. */
    val RotateCcw = WearIcon(
        strokePaths = listOf(
            "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
        ),
    )

    /** Seek forward, and Retry. Lucide `rotate-cw`. */
    val RotateCw = WearIcon(
        strokePaths = listOf(
            "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8",
            "M21 3v5h-5",
        ),
    )

    val Play = WearIcon(fillPaths = listOf("M7 4 L19 12 L7 20 Z"))

    val Pause = WearIcon(
        fillPaths = listOf(
            rect(6f, 4f, 4.4f, 16f, 1.4f),
            rect(13.6f, 4f, 4.4f, 16f, 1.4f),
        ),
    )

    /** Play outline — ambient and chapter markers, where fills are not allowed. */
    val PlayOutline = WearIcon(strokePaths = listOf("M7 4 L19 12 L7 20 Z"), strokeWidth = 1.6f)

    val ChevronUp = WearIcon(strokePaths = listOf("m18 15-6-6-6 6"), strokeWidth = 2.4f)

    /** Scrubbing badge. Lucide `clock`. */
    val Clock = WearIcon(strokePaths = listOf(circle(12f, 12f, 10f), "M12 6v6l4 2"))

    /** Continue Watching header. Lucide `clock` at a shorter hand. */
    val ClockSmall = WearIcon(strokePaths = listOf(circle(12f, 12f, 9f), "M12 7v5l3.5 2"))

    val Volume = WearIcon(
        strokePaths = listOf(
            "M11 4.7a.7.7 0 0 0-1.2-.5L6.4 7.6A1.4 1.4 0 0 1 5.4 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 " +
                "1h2.4a1.4 1.4 0 0 1 1 .4l3.4 3.4a.7.7 0 0 0 1.2-.5z",
            "M16 9a5 5 0 0 1 0 6",
            "M19.4 5.6a9 9 0 0 1 0 12.8",
        ),
    )

    /** Volume, one wave — the compact form used inside sheet headers. */
    val VolumeSmall = WearIcon(
        strokePaths = listOf(
            "M11 4.7a.7.7 0 0 0-1.2-.5L6.4 7.6A1.4 1.4 0 0 1 5.4 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 " +
                "1h2.4a1.4 1.4 0 0 1 1 .4l3.4 3.4a.7.7 0 0 0 1.2-.5z",
            "M16 9a5 5 0 0 1 0 6",
        ),
    )

    /** The crown itself — "Crown: volume". */
    val Crown = WearIcon(strokePaths = listOf(circle(12f, 12f, 9f), "M12 3v3"))

    /** Haptic detent hint. Lucide `sparkle`-ish dot-and-ticks. */
    val Detent = WearIcon(
        strokePaths = listOf(circle(12f, 12f, 3f), "M12 2v3M12 19v3M2 12h3M19 12h3"),
    )

    val Captions = WearIcon(
        strokePaths = listOf(rect(3f, 5f, 18f, 14f, 2f), "M7 15h4M15 15h2M7 11h2M13 11h4"),
    )

    /** Subtitles, sheet-header form. */
    val CaptionsSmall = WearIcon(
        strokePaths = listOf(rect(3f, 5f, 18f, 14f, 2f), "M7 15h4M15 15h2"),
    )

    val ListOrdered = WearIcon(
        strokePaths = listOf(
            "M10 6h11M10 12h11M10 18h11",
            "M4 5h1v4M3.5 15.5a1.5 1.5 0 1 1 2.6 1L3.5 20H6",
        ),
    )

    /** Up Next / pairing target. Lucide `tv`. */
    val Tv = WearIcon(strokePaths = listOf(rect(2f, 7f, 20f, 15f, 2f), "m17 2-5 5-5-5"))

    /** Recenter. Lucide `crosshair`-as-target. */
    val Target = WearIcon(
        strokePaths = listOf(circle(12f, 12f, 9f), circle(12f, 12f, 5f)),
        fillPaths = listOf(circle(12f, 12f, 1.4f)),
    )

    val Mic = WearIcon(
        strokePaths = listOf(
            "M12 19v3",
            "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z",
            "M19 10v2a7 7 0 0 1-14 0v-2",
        ),
    )

    val Headphones = WearIcon(
        strokePaths = listOf(
            "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 " +
                "2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3",
        ),
    )

    val Check = WearIcon(strokePaths = listOf("M20 6 9 17l-5-5"), strokeWidth = 2.6f)

    val Close = WearIcon(strokePaths = listOf("M18 6 6 18M6 6l12 12"), strokeWidth = 2.4f)

    val Minus = WearIcon(strokePaths = listOf("M5 12h14"), strokeWidth = 2.4f)

    val Plus = WearIcon(strokePaths = listOf("M5 12h14M12 5v14"), strokeWidth = 2.4f)
}

/**
 * Draws a [WearIcon] scaled from its 24x24 viewport into the composable's bounds.
 *
 * Stroke width scales with the icon so a 30dp glyph and a 56dp glyph keep the same
 * optical weight — the thing that goes wrong when you scale a vector by bounds alone.
 */
@Composable
fun WearVectorIcon(
    icon: WearIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    // Parsed once per icon, not per frame: PathParser allocates heavily and these
    // redraw on every scrub tick.
    val strokes = remember(icon) { icon.strokePaths.map { it.toComposePath() } }
    val fills = remember(icon) { icon.fillPaths.map { it.toComposePath() } }

    Canvas(
        modifier = modifier.then(
            if (contentDescription != null) {
                Modifier.semantics { this.contentDescription = contentDescription }
            } else {
                Modifier
            },
        ),
    ) {
        val factor = minOf(size.width, size.height) / VIEWPORT
        // Centre the 24x24 box in a non-square slot rather than stretching it.
        translate(
            left = (size.width - VIEWPORT * factor) / 2f,
            top = (size.height - VIEWPORT * factor) / 2f,
        ) {
            scale(scale = factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                fills.forEach { drawPath(it, color = tint) }
                strokes.forEach {
                    drawPath(
                        path = it,
                        color = tint,
                        style = Stroke(
                            width = icon.strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}

private const val VIEWPORT = 24f

private fun String.toComposePath(): Path = PathParser().parsePathString(this).toPath()
