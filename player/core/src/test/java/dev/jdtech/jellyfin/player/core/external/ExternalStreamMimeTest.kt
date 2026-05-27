package dev.jdtech.jellyfin.player.core.external

import androidx.media3.common.MimeTypes
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalStreamMimeTest {
    @Test fun `URL extension canonicalizes adaptive formats ahead of sender hint`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            ExternalStreamMime.canonicalMimeType(
                ExternalStreamRequest(ExternalStreamSource.Url("https://x/master.m3u8?token=a"), "video/mp4"),
            ),
        )
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            ExternalStreamMime.canonicalMimeType(
                ExternalStreamRequest(ExternalStreamSource.Url("https://x/manifest.mpd"), "video/mp4"),
            ),
        )
    }

    @Test fun `inline manifests canonicalize container MIME`() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            ExternalStreamMime.canonicalMimeType(
                ExternalStreamRequest(ExternalStreamSource.Inline("<MPD/>"), "application/dash+xml"),
            ),
        )
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            ExternalStreamMime.canonicalMimeType(
                ExternalStreamRequest(ExternalStreamSource.Inline("#EXTM3U"), "application/vnd.apple.mpegurl"),
            ),
        )
    }
}
