package dev.jdtech.jellyfin.player.xr

import android.media.AudioFormat
import androidx.media3.common.util.Util
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrSpatialAudioControllerTest {

    @Test
    fun `isSpatialPointSourceEligible returns true for standard flat PCM playback`() {
        assertTrue(
            XrSpatialAudioController.isSpatialPointSourceEligible(
                hasSpatialAudioCapability = true,
                hasTargetEntity = true,
                immersiveProjection = false,
                splitAvMuted = false,
                isLinearPcm = Util.isEncodingLinearPcm(AudioFormat.ENCODING_PCM_16BIT),
                tunneling = false,
                offload = false,
            ),
        )
        assertTrue(
            XrSpatialAudioController.isSpatialPointSourceEligible(
                hasSpatialAudioCapability = true,
                hasTargetEntity = true,
                immersiveProjection = false,
                splitAvMuted = false,
                isLinearPcm = Util.isEncodingLinearPcm(AudioFormat.ENCODING_PCM_FLOAT),
                tunneling = false,
                offload = false,
            ),
        )
    }

    @Test
    fun `isSpatialPointSourceEligible rejects compressed bitstream passthrough encodings`() {
        val bitstreamEncodings = listOf(
            AudioFormat.ENCODING_AC3,
            AudioFormat.ENCODING_E_AC3,
            AudioFormat.ENCODING_E_AC3_JOC,
            AudioFormat.ENCODING_DOLBY_TRUEHD,
            AudioFormat.ENCODING_DTS,
            AudioFormat.ENCODING_DTS_HD,
        )
        for (encoding in bitstreamEncodings) {
            assertFalse(
                "Expected encoding=$encoding to be rejected",
                XrSpatialAudioController.isSpatialPointSourceEligible(
                    hasSpatialAudioCapability = true,
                    hasTargetEntity = true,
                    immersiveProjection = false,
                    splitAvMuted = false,
                    isLinearPcm = Util.isEncodingLinearPcm(encoding),
                    tunneling = false,
                    offload = false,
                ),
            )
        }
    }

    @Test
    fun `isSpatialPointSourceEligible rejects immersive projection, splitAv, tunneling, offload, and missing capability`() {
        assertFalse(
            XrSpatialAudioController.isSpatialPointSourceEligible(
                hasSpatialAudioCapability = false,
                hasTargetEntity = true,
                immersiveProjection = false,
                splitAvMuted = false,
                isLinearPcm = true,
                tunneling = false,
                offload = false,
            ),
        )
        assertFalse(
            XrSpatialAudioController.isSpatialPointSourceEligible(
                hasSpatialAudioCapability = true,
                hasTargetEntity = false,
                immersiveProjection = false,
                splitAvMuted = false,
                isLinearPcm = true,
                tunneling = false,
                offload = false,
            ),
        )
        assertFalse(
            XrSpatialAudioController.isSpatialPointSourceEligible(
                hasSpatialAudioCapability = true,
                hasTargetEntity = true,
                immersiveProjection = true,
                splitAvMuted = false,
                isLinearPcm = true,
                tunneling = false,
                offload = false,
            ),
        )
        assertFalse(
            XrSpatialAudioController.isSpatialPointSourceEligible(
                hasSpatialAudioCapability = true,
                hasTargetEntity = true,
                immersiveProjection = false,
                splitAvMuted = true,
                isLinearPcm = true,
                tunneling = false,
                offload = false,
            ),
        )
        assertFalse(
            XrSpatialAudioController.isSpatialPointSourceEligible(
                hasSpatialAudioCapability = true,
                hasTargetEntity = true,
                immersiveProjection = false,
                splitAvMuted = false,
                isLinearPcm = true,
                tunneling = true,
                offload = false,
            ),
        )
        assertFalse(
            XrSpatialAudioController.isSpatialPointSourceEligible(
                hasSpatialAudioCapability = true,
                hasTargetEntity = true,
                immersiveProjection = false,
                splitAvMuted = false,
                isLinearPcm = true,
                tunneling = false,
                offload = true,
            ),
        )
    }
}
