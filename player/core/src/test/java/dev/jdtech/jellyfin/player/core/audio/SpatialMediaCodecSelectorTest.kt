package dev.jdtech.jellyfin.player.core.audio

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SpatialMediaCodecSelectorTest {

    private fun createFakeCodecInfo(name: String, mimeType: String): MediaCodecInfo {
        return MediaCodecInfo.newInstance(
            name,
            mimeType,
            mimeType,
            null,
            false,
            true,
            true,
            false,
            false,
        )
    }

    @Test
    fun `filters out c2 dolby eac3 decoders for audio eac3`() {
        val dolbyDecoder = createFakeCodecInfo("c2.dolby.eac3.decoder.eac3", MimeTypes.AUDIO_E_AC3)
        val dolbyAc3Decoder = createFakeCodecInfo("c2.dolby.eac3.decoder.ac3", MimeTypes.AUDIO_E_AC3)
        val qcomDecoder = createFakeCodecInfo("c2.qcom.audio.decoder.eac3", MimeTypes.AUDIO_E_AC3)

        val fakeSelector = MediaCodecSelector { _, _, _ ->
            listOf(dolbyDecoder, dolbyAc3Decoder, qcomDecoder)
        }

        val selector = SpatialMediaCodecSelector(fakeSelector)
        val result = selector.getDecoderInfos(MimeTypes.AUDIO_E_AC3, false, false)

        assertEquals(1, result.size)
        assertEquals("c2.qcom.audio.decoder.eac3", result.first().name)
    }

    @Test
    fun `returns empty list when only c2 dolby eac3 decoder exists so Ffmpeg handles decoding`() {
        val dolbyDecoder = createFakeCodecInfo("c2.dolby.eac3.decoder.eac3", MimeTypes.AUDIO_E_AC3)

        val fakeSelector = MediaCodecSelector { _, _, _ ->
            listOf(dolbyDecoder)
        }

        val selector = SpatialMediaCodecSelector(fakeSelector)
        val result = selector.getDecoderInfos(MimeTypes.AUDIO_E_AC3, false, false)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters out c2 dolby eac3 decoder for audio eac3-joc`() {
        val dolbyDecoder = createFakeCodecInfo("c2.dolby.eac3.decoder", MimeTypes.AUDIO_E_AC3_JOC)

        val fakeSelector = MediaCodecSelector { _, _, _ ->
            listOf(dolbyDecoder)
        }

        val selector = SpatialMediaCodecSelector(fakeSelector)
        val result = selector.getDecoderInfos(MimeTypes.AUDIO_E_AC3_JOC, false, false)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `preserves standard decoders for other mime types`() {
        val aacDecoder = createFakeCodecInfo("c2.android.aac.decoder", MimeTypes.AUDIO_AAC)
        val hevcDecoder = createFakeCodecInfo("c2.google.hevc.decoder", MimeTypes.VIDEO_H265)

        val fakeSelector = MediaCodecSelector { mimeType, _, _ ->
            when (mimeType) {
                MimeTypes.AUDIO_AAC -> listOf(aacDecoder)
                MimeTypes.VIDEO_H265 -> listOf(hevcDecoder)
                else -> emptyList()
            }
        }

        val selector = SpatialMediaCodecSelector(fakeSelector)

        val aacResult = selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false)
        assertEquals(1, aacResult.size)
        assertEquals("c2.android.aac.decoder", aacResult.first().name)

        val hevcResult = selector.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
        assertEquals(1, hevcResult.size)
        assertEquals("c2.google.hevc.decoder", hevcResult.first().name)
    }
}
