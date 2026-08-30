package dev.spatialfin.companion.wear.tiles

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.wear.presentation.WearMainActivity
import dev.spatialfin.companion.wear.transport.WearTransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import timber.log.Timber
import javax.inject.Inject

/**
 * Frame 16 — the Now Playing tile.
 *
 * Cover art, the timeline arc, and three real transport targets. Same three
 * [ActionBuilders.LoadAction] clickables as before, given shapes you can hit
 * without looking.
 */
@AndroidEntryPoint
class NowPlayingTileService : TileService() {

    @Inject
    lateinit var transportManager: WearTransportManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) =
        serviceScope.future {
            Timber.d("NowPlayingTileService: onTileRequest received")

            // ProtoLayout Clickables can only launch an activity or re-request the tile.
            // We take the second option: the tap arrives here as a clickable id, we run
            // the command, then render the refreshed state in the same pass. No
            // trampoline activity, so the watch face never flashes.
            when (requestParams.currentState.lastClickableId) {
                ID_PLAY_PAUSE -> transportManager.dispatchAction(WearPlayerAction.TogglePlayPause)
                ID_REWIND -> transportManager.dispatchAction(WearPlayerAction.SeekBackward(10))
                ID_FORWARD -> transportManager.dispatchAction(WearPlayerAction.SeekForward(10))
                else -> Unit
            }

            val nowPlaying = transportManager.nowPlaying.value
            val title = nowPlaying?.title?.ifBlank { "SpatialFin" } ?: "SpatialFin"
            val isPlaying = nowPlaying?.isPlaying ?: false
            val position = nowPlaying?.positionSeconds ?: 0L
            val duration = nowPlaying?.durationSeconds ?: 0L
            val progress = if (duration > 0) position.toFloat() / duration else 0f
            val hasArt = transportManager.coverArt.value != null

            val root = LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(
                            ModifiersBuilders.Clickable.Builder()
                                .setOnClick(
                                    ActionBuilders.LaunchAction.Builder()
                                        .setAndroidActivity(
                                            ActionBuilders.AndroidActivity.Builder()
                                                .setPackageName(packageName)
                                                .setClassName(WearMainActivity::class.java.name)
                                                .build(),
                                        )
                                        .build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .apply {
                    TileChrome.coverArtBackground(hasArt)?.let { addContent(it) }
                    addContent(TileChrome.scrim())
                    addContent(TileChrome.progressArcTrack())
                    addContent(TileChrome.progressArcFill(progress))
                }
                .addContent(
                    LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.wrap())
                        .setHeight(DimensionBuilders.wrap())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(TileChrome.eyebrow("NOW PLAYING"))
                        .addContent(TileChrome.spacerH(2f))
                        .addContent(TileChrome.title(title))
                        .addContent(TileChrome.spacerH(2f))
                        .addContent(
                            TileChrome.caption("${formatClock(position)} / ${formatClock(duration)}"),
                        )
                        .addContent(TileChrome.spacerH(12f))
                        .addContent(
                            LayoutElementBuilders.Row.Builder()
                                .setWidth(DimensionBuilders.wrap())
                                .setHeight(DimensionBuilders.wrap())
                                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                                .addContent(
                                    TileChrome.circleButton(
                                        glyph = "−10",
                                        clickableId = ID_REWIND,
                                        sizeDp = 38f,
                                        containerColor = TileChrome.COLOR_BUTTON,
                                        contentColor = TileChrome.COLOR_TITLE,
                                        fontSize = 12f,
                                    ),
                                )
                                .addContent(TileChrome.spacerW(11f))
                                .addContent(
                                    TileChrome.circleButton(
                                        glyph = if (isPlaying) "▌▌" else "▶",
                                        clickableId = ID_PLAY_PAUSE,
                                        sizeDp = 52f,
                                        containerColor = TileChrome.COLOR_PRIMARY,
                                        contentColor = TileChrome.COLOR_ON_PRIMARY,
                                        fontSize = 18f,
                                    ),
                                )
                                .addContent(TileChrome.spacerW(11f))
                                .addContent(
                                    TileChrome.circleButton(
                                        glyph = "+10",
                                        clickableId = ID_FORWARD,
                                        sizeDp = 38f,
                                        containerColor = TileChrome.COLOR_BUTTON,
                                        contentColor = TileChrome.COLOR_TITLE,
                                        fontSize = 12f,
                                    ),
                                )
                                .build(),
                        )
                        .build(),
                )
                .build()

            TileBuilders.Tile.Builder()
                .setResourcesVersion(resourcesVersion())
                .setTileTimeline(
                    TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(
                                    LayoutElementBuilders.Layout.Builder().setRoot(root).build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        serviceScope.future {
            val builder = ResourceBuilders.Resources.Builder().setVersion(resourcesVersion())
            transportManager.coverArt.value?.let { art ->
                builder.addIdToImageMapping(TileChrome.ID_COVER_ART, TileChrome.inlineCoverArt(art))
            }
            builder.build()
        }

    /**
     * Keyed on the item, not a constant.
     *
     * The system caches tile resources by version string, so a fixed "1" would pin
     * the first poster the tile ever drew for the life of the install.
     */
    private fun resourcesVersion(): String =
        transportManager.nowPlaying.value?.itemId?.takeIf { it.isNotBlank() } ?: "empty"

    private fun formatClock(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val ID_PLAY_PAUSE = "np_play_pause"
        private const val ID_REWIND = "np_rewind"
        private const val ID_FORWARD = "np_forward"
    }
}
