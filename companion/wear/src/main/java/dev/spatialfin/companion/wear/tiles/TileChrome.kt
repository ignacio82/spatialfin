package dev.spatialfin.companion.wear.tiles

import android.graphics.Bitmap
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import java.nio.ByteBuffer

/**
 * Shared tile chrome for the redesigned Now Playing and Up Next tiles.
 *
 * ProtoLayout can do artwork, arcs and image buttons; the tiles were three plain
 * text labels because nothing here had been built, not because the surface is
 * limited. Everything below is deliberately hand-built rather than taken from
 * `protolayout-material3`: the redesign's geometry (a 310-degree arc opening at
 * the bottom, a 52dp centre target flanked by 38dp circles) is not one of the
 * Material tile layouts, and bending one into shape costs more than composing it.
 */
internal object TileChrome {

    const val COLOR_PRIMARY = 0xFFA4C9FE.toInt()
    const val COLOR_ON_PRIMARY = 0xFF00315B.toInt()
    const val COLOR_TITLE = 0xFFF2F3F8.toInt()
    const val COLOR_BODY = 0xFFC4C6D0.toInt()
    const val COLOR_TRACK = 0x28E2E2E9
    const val COLOR_BUTTON = 0xE61E212B.toInt()
    const val COLOR_SCRIM = 0xF504060A.toInt()

    /** Matches the app's arc: a 310-degree sweep with the gap centred on 6 o'clock. */
    const val ARC_SWEEP_DEGREES = 310f

    const val ID_COVER_ART = "cover_art"

    /**
     * The timeline arc.
     *
     * Anchored at the arc's own start rather than at 12 o'clock, so progress grows
     * from the same place the phone and headset draw it from.
     */
    fun progressArcTrack(): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(DimensionBuilders.degrees(ARC_ANCHOR_DEGREES))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(DimensionBuilders.degrees(ARC_SWEEP_DEGREES))
                    .setThickness(DimensionBuilders.dp(4f))
                    .setColor(ColorBuilders.argb(COLOR_TRACK))
                    .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                    .build(),
            )
            .build()

    fun progressArcFill(progress: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(DimensionBuilders.degrees(ARC_ANCHOR_DEGREES))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(
                        DimensionBuilders.degrees(ARC_SWEEP_DEGREES * progress.coerceIn(0f, 1f)),
                    )
                    .setThickness(DimensionBuilders.dp(4f))
                    .setColor(ColorBuilders.argb(COLOR_PRIMARY))
                    .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                    .build(),
            )
            .build()

    /** Uppercase eyebrow — "NOW PLAYING" / "UP NEXT". */
    fun eyebrow(text: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(11f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                    .setLetterSpacing(DimensionBuilders.em(0.12f))
                    .setColor(ColorBuilders.argb(COLOR_PRIMARY))
                    .build(),
            )
            .build()

    fun title(text: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(18f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(ColorBuilders.argb(COLOR_TITLE))
                    .build(),
            )
            .build()

    fun caption(text: String, color: Int = COLOR_BODY): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(13f))
                    .setColor(ColorBuilders.argb(color))
                    .build(),
            )
            .build()

    fun spacerH(dp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build()

    fun spacerW(dp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(dp)).build()

    /**
     * A round transport target.
     *
     * [glyph] is text rather than a vector because a tile resource set is the only
     * way to ship a drawable here, and three glyphs do not justify one — but the
     * shape, size and colour are the design's, so the target is a real 38/52dp
     * circle instead of a bare tappable label.
     */
    fun circleButton(
        glyph: String,
        clickableId: String,
        sizeDp: Float,
        containerColor: Int,
        contentColor: Int,
        fontSize: Float,
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(sizeDp))
            .setHeight(DimensionBuilders.dp(sizeDp))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(containerColor))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(sizeDp / 2f))
                                    .build(),
                            )
                            .build(),
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(clickableId)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(glyph)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(fontSize))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .setColor(ColorBuilders.argb(contentColor))
                            .build(),
                    )
                    .build(),
            )
            .build()

    /** A pill target — the Up Next tile's explicit Resume. */
    fun pillButton(label: String, clickableId: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setHeight(DimensionBuilders.dp(38f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(COLOR_PRIMARY))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(19f))
                                    .build(),
                            )
                            .build(),
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(clickableId)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(18f))
                            .setEnd(DimensionBuilders.dp(18f))
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(15f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .setColor(ColorBuilders.argb(COLOR_ON_PRIMARY))
                            .build(),
                    )
                    .build(),
            )
            .build()

    /** Full-bleed cover art behind the tile, or null when there is none to draw. */
    fun coverArtBackground(hasArt: Boolean): LayoutElementBuilders.LayoutElement? =
        if (!hasArt) {
            null
        } else {
            LayoutElementBuilders.Image.Builder()
                .setResourceId(ID_COVER_ART)
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_CROP)
                .build()
        }

    /**
     * The scrim over the art.
     *
     * Not decoration: 18sp white over an arbitrary poster is unreadable, and a tile
     * has no chance to re-render once the user has glanced at it.
     */
    fun scrim(): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(COLOR_SCRIM))
                            .build(),
                    )
                    .build(),
            )
            .build()

    /**
     * Packs [bitmap] into an RGB_565 inline resource.
     *
     * RGB_565 rather than ARGB_8888 because a tile's resource payload crosses an
     * IPC boundary on every refresh and the art is opaque anyway — half the bytes
     * for no visible loss behind a scrim.
     */
    fun inlineCoverArt(bitmap: Bitmap): ResourceBuilders.ImageResource {
        val scaled = bitmap.scaleToTileArt()
        val buffer = ByteBuffer.allocate(scaled.byteCount)
        scaled.copyPixelsToBuffer(buffer)
        if (scaled !== bitmap) scaled.recycle()
        return ResourceBuilders.ImageResource.Builder()
            .setInlineResource(
                ResourceBuilders.InlineImageResource.Builder()
                    .setData(buffer.array())
                    .setWidthPx(scaled.width)
                    .setHeightPx(scaled.height)
                    .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565)
                    .build(),
            )
            .build()
    }

    private fun Bitmap.scaleToTileArt(): Bitmap {
        val target = TILE_ART_PX
        return if (width == target && height == target && config == Bitmap.Config.RGB_565) {
            this
        } else {
            Bitmap.createScaledBitmap(this, target, target, true)
                .copy(Bitmap.Config.RGB_565, false)
        }
    }

    /**
     * The arc starts 115 degrees clockwise from 3 o'clock; ProtoLayout measures its
     * anchor clockwise from 12, so the same opening is 205 degrees here.
     */
    private const val ARC_ANCHOR_DEGREES = 205f

    /** Big enough to fill a 454px screen without shipping a full-resolution poster. */
    private const val TILE_ART_PX = 240
}
