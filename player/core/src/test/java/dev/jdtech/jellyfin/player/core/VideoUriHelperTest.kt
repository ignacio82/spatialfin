package dev.jdtech.jellyfin.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUriHelperTest {

    @Test
    fun testIsSupportedVideoMimeType() {
        assertTrue(VideoUriHelper.isSupportedVideoMimeType("video/mp4"))
        assertTrue(VideoUriHelper.isSupportedVideoMimeType("video/x-matroska"))
        assertTrue(VideoUriHelper.isSupportedVideoMimeType("video/webm"))
        assertTrue(VideoUriHelper.isSupportedVideoMimeType("video/*"))
        assertTrue(VideoUriHelper.isSupportedVideoMimeType("application/x-matroska"))
        assertTrue(VideoUriHelper.isSupportedVideoMimeType("application/mp4"))
        assertTrue(VideoUriHelper.isSupportedVideoMimeType("application/dash+xml"))
        assertTrue(VideoUriHelper.isSupportedVideoMimeType("application/vnd.apple.mpegurl"))

        assertFalse(VideoUriHelper.isSupportedVideoMimeType(null))
        assertFalse(VideoUriHelper.isSupportedVideoMimeType(""))
        assertFalse(VideoUriHelper.isSupportedVideoMimeType("audio/mpeg"))
        assertFalse(VideoUriHelper.isSupportedVideoMimeType("image/png"))
        assertFalse(VideoUriHelper.isSupportedVideoMimeType("text/plain"))
    }

    @Test
    fun testIsSupportedVideoExtension() {
        assertTrue(VideoUriHelper.isSupportedVideoExtension("movie.mp4"))
        assertTrue(VideoUriHelper.isSupportedVideoExtension("clip.MKV"))
        assertTrue(VideoUriHelper.isSupportedVideoExtension("/sdcard/Movies/trailer.webm"))
        assertTrue(VideoUriHelper.isSupportedVideoExtension("stream.m3u8?token=123"))
        assertTrue(VideoUriHelper.isSupportedVideoExtension("film.avi"))
        assertTrue(VideoUriHelper.isSupportedVideoExtension("3d_sbs.mov"))
        assertTrue(VideoUriHelper.isSupportedVideoExtension("video.ts"))

        assertFalse(VideoUriHelper.isSupportedVideoExtension(null))
        assertFalse(VideoUriHelper.isSupportedVideoExtension(""))
        assertFalse(VideoUriHelper.isSupportedVideoExtension("song.mp3"))
        assertFalse(VideoUriHelper.isSupportedVideoExtension("photo.jpg"))
        assertFalse(VideoUriHelper.isSupportedVideoExtension("doc.pdf"))
    }
}
