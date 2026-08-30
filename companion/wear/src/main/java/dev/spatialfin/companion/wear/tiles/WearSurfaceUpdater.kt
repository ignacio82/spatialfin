package dev.spatialfin.companion.wear.tiles

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.wear.complications.SpatialFinComplicationProviderService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes tile and complication refreshes when host state changes.
 *
 * Tiles and complications never poll: `setFreshnessIntervalMillis` is coarse (tens of
 * minutes) and the platform charges the wakeups to us anyway. The Data Layer is the
 * event source, so a state item arriving is what triggers a redraw. The platform still
 * throttles these — budget seconds, not milliseconds.
 */
@Singleton
class WearSurfaceUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val complicationRequester by lazy {
        ComplicationDataSourceUpdateRequester.create(
            context = context,
            complicationDataSourceComponent = ComponentName(
                context,
                SpatialFinComplicationProviderService::class.java,
            ),
        )
    }

    fun requestNowPlayingUpdate() {
        runCatching {
            TileService.getUpdater(context).requestUpdate(NowPlayingTileService::class.java)
            complicationRequester.requestUpdateAll()
        }.onFailure { Timber.w(it, "WearSurfaceUpdater: now-playing surface update failed") }
    }

    fun requestUpNextUpdate() {
        runCatching {
            TileService.getUpdater(context).requestUpdate(UpNextTileService::class.java)
        }.onFailure { Timber.w(it, "WearSurfaceUpdater: up-next tile update failed") }
    }

    fun requestVitalsUpdate() {
        runCatching { complicationRequester.requestUpdateAll() }
            .onFailure { Timber.w(it, "WearSurfaceUpdater: vitals complication update failed") }
    }
}
