package dev.jdtech.jellyfin.player.core.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import dev.jdtech.jellyfin.player.core.sync.AudioSubtitleSyncController
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSyncOffsetSinkTest {

    @Test
    fun `negative audio offset shifts getCurrentPositionUs back to hold video frames`() {
        val delegate = mockk<AudioSink>(relaxed = true)
        every { delegate.getCurrentPositionUs(false) } returns 10_000_000L // 10.0s in us

        val controller = AudioSubtitleSyncController()
        val sink = AudioSyncOffsetSink(delegate, controller)

        controller.setAudioOffsetMs(-200L) // -200 ms -> -200,000 us
        assertEquals(9_800_000L, sink.getCurrentPositionUs(false))

        // Clamped at 0 us when negative offset exceeds current position
        every { delegate.getCurrentPositionUs(false) } returns 100_000L
        assertEquals(0L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun `positive and zero audio offsets leave getCurrentPositionUs unchanged`() {
        val delegate = mockk<AudioSink>(relaxed = true)
        every { delegate.getCurrentPositionUs(false) } returns 10_000_000L

        val controller = AudioSubtitleSyncController()
        val sink = AudioSyncOffsetSink(delegate, controller)

        controller.setAudioOffsetMs(0L)
        assertEquals(10_000_000L, sink.getCurrentPositionUs(false))

        controller.setAudioOffsetMs(350L)
        assertEquals(10_000_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun `preserves CURRENT_POSITION_NOT_SET from delegate`() {
        val delegate = mockk<AudioSink>(relaxed = true)
        every { delegate.getCurrentPositionUs(true) } returns AudioSink.CURRENT_POSITION_NOT_SET

        val controller = AudioSubtitleSyncController()
        controller.setAudioOffsetMs(-300L)
        val sink = AudioSyncOffsetSink(delegate, controller)

        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(true))
    }

    @Test
    fun `detects when positive delay is bypassed by compressed bitstream passthrough`() {
        val delegate = mockk<AudioSink>(relaxed = true)
        val controller = AudioSubtitleSyncController()
        val sink = AudioSyncOffsetSink(delegate, controller)

        val pcmFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(pcmFormat).build())
        controller.setAudioOffsetMs(250L)
        assertFalse(sink.isPositiveDelayBypassedByPassthrough())

        val trueHdFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setPcmEncoding(C.ENCODING_DOLBY_TRUEHD)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(trueHdFormat).build())
        assertTrue(sink.isPositiveDelayBypassedByPassthrough())

        // Negative offsets work even during bitstream passthrough via clock shifting
        controller.setAudioOffsetMs(-250L)
        assertFalse(sink.isPositiveDelayBypassedByPassthrough())
    }
}
