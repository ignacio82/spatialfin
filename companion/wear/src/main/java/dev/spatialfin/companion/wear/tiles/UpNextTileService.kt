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
import dev.spatialfin.companion.wear.presentation.formatRemaining
import dev.spatialfin.companion.wear.transport.WearTransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import timber.log.Timber
import javax.inject.Inject

/**
 * Frame 17 — the Up Next tile.
 *
 * One explicit Resume target rather than a whole-tile tap. The old layout started
 * playback anywhere you touched it, which meant you could not scroll past the tile
 * on the tile carousel without risking a play command on the headset.
 */
@AndroidEntryPoint
class UpNextTileService : TileService() {

    @Inject
    lateinit var transportManager: WearTransportManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) =
        serviceScope.future {
            Timber.d("UpNextTileService: onTileRequest received")
            val firstItem = transportManager.nextUp.value?.items?.firstOrNull()

            // The tap comes back as a LoadAction clickable id, so the command runs here
            // and the tile re-renders with the result. Launching the app instead would
            // defeat the point.
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
            val remaining = firstItem
                ?.let { it.durationSeconds - it.playbackPositionSeconds }
                ?.takeIf { it > 0 }
            val caption = feedback
                ?: remaining?.let { formatRemaining(it) }
                ?: firstItem?.seriesName
                ?: "Nothing queued"

            val root = LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                // The tile body opens the app; only the Resume pill plays. Scrolling
                // past can no longer start a movie.
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(
                            ModifiersBuilders.Clickable.Builder()
                                .setId(ID_OPEN)
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
                // No backdrop here: Next Up art arrives as a Jellyfin URL, and a tile's
                // resource set takes bytes, not URLs — a tile process cannot go and fetch
                // one. The design's poster returns once WearNextUpPublisher pushes the
                // first item's art as a Data Layer Asset the way now-playing already does.
                .addContent(TileChrome.scrim())
                .addContent(
                    LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.wrap())
                        .setHeight(DimensionBuilders.wrap())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(TileChrome.eyebrow("UP NEXT"))
                        .addContent(TileChrome.spacerH(3f))
                        .addContent(TileChrome.title(title))
                        .addContent(TileChrome.spacerH(2f))
                        .addContent(TileChrome.caption(caption))
                        .addContent(TileChrome.spacerH(14f))
                        .apply {
                            if (firstItem != null) {
                                addContent(TileChrome.pillButton("Resume", ID_PLAY))
                            }
                        }
                        .build(),
                )
                .build()

            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
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
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ID_PLAY = "un_play"
        private const val ID_OPEN = "un_open"
    }
}
