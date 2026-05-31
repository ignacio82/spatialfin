package dev.jdtech.jellyfin.player.core.external

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.ExoPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamSource

/**
 * Builds transient Media3 sources for inbound FCast playback. Each replacement request creates
 * fresh source factories so sender headers apply to that request's manifest and segments only.
 */
@UnstableApi
class ExternalStreamMediaPreparer(
    private val context: Context,
    private val parseSubtitlesDuringExtraction: Boolean = true,
) {
    data class Prepared(
        val mediaSource: MediaSource,
        val startPositionMs: Long,
        val initialVolume: Float?,
        val initialSpeed: Float?,
    )

    fun prepare(request: ExternalStreamRequest): Prepared {
        val inline = request.source as? ExternalStreamSource.Inline
        val networkFactory = DefaultDataSource.Factory(
            context,
            PluginHttpDataSourceFactory.create(request.headers)
        )
        val dataSourceFactory: DataSource.Factory =
            if (inline != null) {
                InlineRoutingDataSourceFactory(
                    inlineBytes = inline.content.toByteArray(Charsets.UTF_8),
                    networkFactory = networkFactory,
                )
            } else {
                networkFactory
            }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        val item = MediaItem.Builder()
            .setUri(sourceUri(request))
            .setMimeType(ExternalStreamMime.canonicalMimeType(request))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title ?: "FCast media")
                    .apply {
                        request.thumbnailUrl?.takeIf(String::isNotBlank)
                            ?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build(),
            )
            .build()
        return Prepared(
            mediaSource = mediaSourceFactory.createMediaSource(item),
            startPositionMs = (request.startPositionSeconds * 1000.0).toLong().coerceAtLeast(0L),
            initialVolume = request.initialVolume?.toFloat()?.coerceIn(0f, 1f),
            initialSpeed = request.initialSpeed?.toFloat()?.coerceIn(0.25f, 4f),
        )
    }

    fun replace(player: ExoPlayer, request: ExternalStreamRequest) {
        val prepared = prepare(request)
        player.setMediaSource(prepared.mediaSource, prepared.startPositionMs)
        prepared.initialVolume?.let { player.volume = it }
        prepared.initialSpeed?.let { player.setPlaybackSpeed(it) }
        player.prepare()
    }

    private fun sourceUri(request: ExternalStreamRequest): Uri = when (val source = request.source) {
        is ExternalStreamSource.Url -> Uri.parse(source.url)
        is ExternalStreamSource.Inline -> Uri.parse(
            if (ExternalStreamMime.canonicalMimeType(request) == MimeTypes.APPLICATION_M3U8) {
                "$INLINE_SCHEME:///manifest.m3u8"
            } else {
                "$INLINE_SCHEME:///manifest.mpd"
            },
        )
    }

    private companion object {
        const val INLINE_SCHEME = "fcast-inline"
    }
}

/** MIME policy is pure so URI/container regressions can be verified without Android playback. */
object ExternalStreamMime {
    fun canonicalMimeType(request: ExternalStreamRequest): String? {
        if (request.source is ExternalStreamSource.Inline) {
            return canonicalContainer(request.container)
        }
        val url = (request.source as ExternalStreamSource.Url).url.substringBefore('?').lowercase()
        return when {
            url.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            url.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> canonicalContainer(request.container)
        }
    }

    private fun canonicalContainer(container: String): String? = when (val value = container.trim().lowercase()) {
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl" ->
            MimeTypes.APPLICATION_M3U8
        "application/dash+xml", "application/mpd", "application/x-mpegdash" ->
            MimeTypes.APPLICATION_MPD
        "" -> null
        else -> container
    }
}

@UnstableApi
private class InlineRoutingDataSourceFactory(
    private val inlineBytes: ByteArray,
    private val networkFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        InlineRoutingDataSource(inlineBytes, networkFactory)
}

@UnstableApi
private class InlineRoutingDataSource(
    private val inlineBytes: ByteArray,
    private val networkFactory: DataSource.Factory,
) : DataSource {
    private var active: DataSource? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "fcast-inline") {
            ByteArrayDataSource(inlineBytes)
        } else {
            networkFactory.createDataSource()
        }
        transferListeners.forEach(source::addTransferListener)
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders.orEmpty()

    override fun close() {
        active?.close()
        active = null
    }
}
