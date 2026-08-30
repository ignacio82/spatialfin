package dev.spatialfin.companion.wear.complications

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.companion.wear.R
import dev.spatialfin.companion.wear.presentation.WearMainActivity
import dev.spatialfin.companion.wear.transport.WearTransportManager
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SpatialFinComplicationProviderService : SuspendingComplicationDataSourceService() {

    @Inject
    lateinit var transportManager: WearTransportManager

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("Dune").build(),
                    contentDescription = PlainComplicationText.Builder("Now Playing").build(),
                ).build()
            }
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 85f,
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder("Headset Battery").build(),
                ).setText(PlainComplicationText.Builder("85%").build()).build()
            }
            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(
                        image = Icon.createWithResource(this, R.drawable.ic_launcher_wear),
                        type = SmallImageType.ICON,
                    ).build(),
                    contentDescription = PlainComplicationText.Builder("SpatialFin").build(),
                ).build()
            }
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Timber.d("SpatialFinComplicationProviderService: request for type %s", request.complicationType)
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> {
                val nowPlaying = transportManager.nowPlaying.value
                val title = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Idle"
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(title).build(),
                    contentDescription = PlainComplicationText.Builder("SpatialFin Now Playing").build(),
                )
                    .setTapAction(launchIntent)
                    .setMonochromaticImage(
                        MonochromaticImage.Builder(
                            Icon.createWithResource(this, R.drawable.ic_launcher_wear),
                        ).build(),
                    )
                    .build()
            }

            ComplicationType.RANGED_VALUE -> {
                val vitals = transportManager.vitals.value
                val battery = vitals?.batteryPercent?.coerceIn(0, 100)?.toFloat() ?: 0f
                RangedValueComplicationData.Builder(
                    value = battery,
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder("Headset Battery").build(),
                )
                    .setText(PlainComplicationText.Builder("${battery.toInt()}%").build())
                    .setTitle(PlainComplicationText.Builder("XR").build())
                    .setTapAction(launchIntent)
                    .build()
            }

            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(
                        image = Icon.createWithResource(this, R.drawable.ic_launcher_wear),
                        type = SmallImageType.ICON,
                    ).build(),
                    contentDescription = PlainComplicationText.Builder("Open SpatialFin").build(),
                )
                    .setTapAction(launchIntent)
                    .build()
            }

            else -> null
        }
    }
}
