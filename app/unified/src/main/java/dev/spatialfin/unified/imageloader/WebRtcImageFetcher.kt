package dev.spatialfin.unified.imageloader

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import dev.jdtech.jellyfin.sendspin.receiver.MusicAssistantRemoteBridge
import okio.Buffer

class WebRtcImageFetcher(
    private val data: Uri,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val fetcher = MusicAssistantRemoteBridge.remoteImageFetcher ?: return null
        val proxyPath = buildProxyPath() ?: return null
        
        val response = runCatching { fetcher(proxyPath) }.getOrNull() ?: return null
        if (response.status !in HTTP_OK_RANGE) return null
        
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().apply { write(response.body) },
                fileSystem = options.fileSystem,
            ),
            mimeType = response.headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value,
            dataSource = DataSource.NETWORK,
        )
    }

    private fun buildProxyPath(): String? {
        val path = data.path ?: return null
        val query = data.query
        return if (query.isNullOrEmpty()) path else "$path?$query"
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != SCHEME) return null
            return WebRtcImageFetcher(data, options)
        }
    }

    companion object {
        const val SCHEME = "mawebrtc"
        private val HTTP_OK_RANGE = 200..299
    }
}
