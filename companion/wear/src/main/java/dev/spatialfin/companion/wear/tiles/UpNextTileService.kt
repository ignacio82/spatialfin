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
class UpNextTileService : TileService() {

    @Inject
    lateinit var transportManager: WearTransportManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) =
        serviceScope.future {
            Timber.d("UpNextTileService: onTileRequest received")
            val nextUpState = transportManager.nextUp.value
            val firstItem = nextUpState?.items?.firstOrNull()

            // "Single tap starts playback on the primary headset or TV" — the tap comes
            // back as a LoadAction clickable id, so the command runs here and the tile
            // re-renders with the result. Launching the app instead would defeat the point.
            var feedback: String? = null
            if (requestParams.currentState.lastClickableId == ID_PLAY && firstItem != null) {
                feedback = transportManager.dispatchAction(
                    WearPlayerAction.PlayMediaItem(
                        itemId = firstItem.id,
                        mediaType = firstItem.mediaType,
                        startPositionMs = firstItem.playbackPositionSeconds * 1000L,
                    ),
                ).getOrNull()
            }

            val title = firstItem?.title ?: "Continue Watching"
            val showName = feedback ?: firstItem?.seriesName ?: "SpatialFin Library"

            val rootLayout = LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(
                            ModifiersBuilders.Clickable.Builder()
                                .setId(if (firstItem != null) ID_PLAY else ID_OPEN)
                                .setOnClick(
                                    if (firstItem != null) {
                                        ActionBuilders.LoadAction.Builder().build()
                                    } else {
                                        ActionBuilders.LaunchAction.Builder()
                                            .setAndroidActivity(
                                                ActionBuilders.AndroidActivity.Builder()
                                                    .setPackageName(packageName)
                                                    .setClassName(WearMainActivity::class.java.name)
                                                    .build(),
                                            )
                                            .build()
                                    },
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
                                .setText("UP NEXT")
                                .setFontStyle(
                                    LayoutElementBuilders.FontStyle.Builder()
                                        .setColor(ColorBuilders.argb(0xFFA4C9FE.toInt()))
                                        .build(),
                                )
                                .build(),
                        )
                        .addContent(
                            LayoutElementBuilders.Spacer.Builder()
                                .setHeight(DimensionBuilders.dp(4f))
                                .build(),
                        )
                        .addContent(
                            LayoutElementBuilders.Text.Builder()
                                .setText(title)
                                .setFontStyle(
                                    LayoutElementBuilders.FontStyle.Builder()
                                        .setColor(ColorBuilders.argb(0xFFFFFFFF.toInt()))
                                        .build(),
                                )
                                .setMaxLines(1)
                                .build(),
                        )
                        .addContent(
                            LayoutElementBuilders.Spacer.Builder()
                                .setHeight(DimensionBuilders.dp(2f))
                                .build(),
                        )
                        .addContent(
                            LayoutElementBuilders.Text.Builder()
                                .setText(showName)
                                .setFontStyle(
                                    LayoutElementBuilders.FontStyle.Builder()
                                        .setColor(ColorBuilders.argb(0xFFC4C6D0.toInt()))
                                        .build(),
                                )
                                .setMaxLines(1)
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

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ID_PLAY = "up_next_play"
        private const val ID_OPEN = "up_next_open"
    }
}
