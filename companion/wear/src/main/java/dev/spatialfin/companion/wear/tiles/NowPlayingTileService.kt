package dev.spatialfin.companion.wear.tiles

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
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
            val subtitle = nowPlaying?.seriesName ?: if (isPlaying) "Playing" else "Ready to play"

            val rootLayout = LayoutElementBuilders.Box.Builder()
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
                .addContent(
                    LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.wrap())
                        .setHeight(DimensionBuilders.wrap())
                        .addContent(
                            LayoutElementBuilders.Text.Builder()
                                .setText(title)
                                .setFontStyle(
                                    LayoutElementBuilders.FontStyle.Builder()
                                        .setColor(ColorBuilders.argb(0xFFA4C9FE.toInt()))
                                        .build(),
                                )
                                .setMaxLines(1)
                                .build(),
                        )
                        .addContent(
                            LayoutElementBuilders.Spacer.Builder()
                                .setHeight(DimensionBuilders.dp(4f))
                                .build(),
                        )
                        .addContent(
                            LayoutElementBuilders.Text.Builder()
                                .setText(subtitle)
                                .setFontStyle(
                                    LayoutElementBuilders.FontStyle.Builder()
                                        .setColor(ColorBuilders.argb(0xFFC4C6D0.toInt()))
                                        .build(),
                                )
                                .setMaxLines(1)
                                .build(),
                        )
                        .addContent(
                            LayoutElementBuilders.Spacer.Builder()
                                .setHeight(DimensionBuilders.dp(8f))
                                .build(),
                        )
                        .addContent(
                            LayoutElementBuilders.Row.Builder()
                                .setWidth(DimensionBuilders.wrap())
                                .setHeight(DimensionBuilders.wrap())
                                .addContent(transportControl("-10", ID_REWIND))
                                .addContent(
                                    LayoutElementBuilders.Spacer.Builder()
                                        .setWidth(DimensionBuilders.dp(12f))
                                        .build(),
                                )
                                .addContent(
                                    transportControl(if (isPlaying) "⏸" else "▶", ID_PLAY_PAUSE),
                                )
                                .addContent(
                                    LayoutElementBuilders.Spacer.Builder()
                                        .setWidth(DimensionBuilders.dp(12f))
                                        .build(),
                                )
                                .addContent(transportControl("+10", ID_FORWARD))
                                .build(),
                        )
                        .build(),
                )
                .build()

            val timelineEntry = TimelineBuilders.TimelineEntry.Builder()
                .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(rootLayout).build())
                .build()

            val timeline = TimelineBuilders.Timeline.Builder()
                .addTimelineEntry(timelineEntry)
                .build()

            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(timeline)
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        serviceScope.future {
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        }

    /** A tappable label wired to a LoadAction, identified by [clickableId]. */
    private fun transportControl(label: String, clickableId: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(label)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setColor(ColorBuilders.argb(0xFFFFFFFF.toInt()))
                    .build(),
            )
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(clickableId)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(DimensionBuilders.dp(6f))
                            .build(),
                    )
                    .build(),
            )
            .build()

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ID_PLAY_PAUSE = "np_play_pause"
        private const val ID_REWIND = "np_rewind"
        private const val ID_FORWARD = "np_forward"
    }
}
