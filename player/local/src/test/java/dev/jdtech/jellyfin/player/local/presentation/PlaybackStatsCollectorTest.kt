package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatsCollectorTest {

    @Test
    fun `accumulates decoder names, dropped frames, underruns, and bandwidth`() {
        val collector = PlaybackStatsCollector()
        val eventTime = mockk<AnalyticsListener.EventTime>(relaxed = true)

        collector.onVideoDecoderInitialized(eventTime, "c2.qti.hevc.decoder", 100L, 12L)
        collector.onAudioDecoderInitialized(eventTime, "c2.android.eac3.decoder", 110L, 5L)
        collector.onDroppedVideoFrames(eventTime, 3, 1_000L)
        collector.onDroppedVideoFrames(eventTime, 2, 1_000L)
        collector.onAudioUnderrun(eventTime, 4096, 85L, 120L)
        collector.onBandwidthEstimate(eventTime, 500, 1_000_000L, 42_500_000L)
        collector.recordRenderedFramesForTesting(1440L)

        val hdrColorInfo = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .build()
        val videoFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hev1.2.4.L153.B0")
            .setWidth(3840)
            .setHeight(2160)
            .setFrameRate(23.976f)
            .setColorInfo(hdrColorInfo)
            .build()
        val audioFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()

        val snapshot = collector.buildSnapshotFromInputs(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            bufferedDurationMs = 15_400L,
            deliveryMethod = "Direct Play",
            transcodeReasons = emptyList(),
            containerFormat = "MKV",
            displayRefreshRateHz = 90f,
            audioModeSummary = "Spatial 3D Point-Source",
            subtitleEngineSummary = "libass JNI (ASS/SSA)",
            subtitleOffsetMs = -150L,
            audioOffsetMs = 200L,
        )

        assertEquals("Direct Play", snapshot.deliveryMethod)
        assertEquals("MKV", snapshot.containerFormat)
        assertEquals("HEVC (hev1.2.4.L153.B0)", snapshot.videoCodec)
        assertEquals("c2.qti.hevc.decoder", snapshot.videoDecoderName)
        assertTrue(snapshot.isHardwareVideoDecoder)
        assertEquals("3840x2160", snapshot.videoResolution)
        assertEquals(23.976f, snapshot.videoFrameRate, 0.001f)
        assertEquals(90f, snapshot.displayRefreshRateHz, 0.001f)
        assertEquals("BT.2020 / PQ (HDR10)", snapshot.colorTransferSummary)
        assertEquals("EAC3-JOC 6ch 48kHz", snapshot.audioCodec)
        assertEquals("c2.android.eac3.decoder", snapshot.audioDecoderName)
        assertEquals("Spatial 3D Point-Source", snapshot.audioModeSummary)
        assertEquals("libass JNI (ASS/SSA)", snapshot.subtitleEngineSummary)
        assertEquals(1440L, snapshot.renderedVideoFrames)
        assertEquals(5L, snapshot.droppedVideoFrames)
        assertEquals(1L, snapshot.audioUnderruns)
        assertEquals(15_400L, snapshot.bufferedDurationMs)
        assertEquals(42.5f, snapshot.estimatedBandwidthMbps, 0.01f)
        assertEquals(-150L, snapshot.subtitleOffsetMs)
        assertEquals(200L, snapshot.audioOffsetMs)
    }

    @Test
    fun `classifies hardware vs software video decoders`() {
        assertTrue(PlaybackStatsCollector.isHardwareAcceleratedDecoder("c2.qti.avc.decoder"))
        assertTrue(PlaybackStatsCollector.isHardwareAcceleratedDecoder("OMX.Exynos.HEVC.Decoder"))
        assertFalse(PlaybackStatsCollector.isHardwareAcceleratedDecoder("c2.android.av1.decoder"))
        assertFalse(PlaybackStatsCollector.isHardwareAcceleratedDecoder("OMX.google.h264.decoder"))
        assertFalse(PlaybackStatsCollector.isHardwareAcceleratedDecoder("libdav1d"))
        assertFalse(PlaybackStatsCollector.isHardwareAcceleratedDecoder("None"))
    }
}
