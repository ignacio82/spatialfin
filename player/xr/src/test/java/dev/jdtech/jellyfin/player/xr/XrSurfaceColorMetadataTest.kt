package dev.jdtech.jellyfin.player.xr

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.xr.scenecore.SurfaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XrSurfaceColorMetadataTest {

    @Test
    fun `mapColorSpace maps BT2020, BT601, BT709, and NO_VALUE`() {
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorSpace.BT2020,
            XrSurfaceColorMetadata.mapColorSpace(C.COLOR_SPACE_BT2020),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorSpace.BT601_PAL,
            XrSurfaceColorMetadata.mapColorSpace(C.COLOR_SPACE_BT601),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorSpace.BT709,
            XrSurfaceColorMetadata.mapColorSpace(C.COLOR_SPACE_BT709),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorSpace.BT709,
            XrSurfaceColorMetadata.mapColorSpace(Format.NO_VALUE),
        )
    }

    @Test
    fun `mapColorTransfer maps ST2084, HLG, LINEAR, GAMMA_2_2, SRGB, SDR, and NO_VALUE`() {
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorTransfer.ST2084,
            XrSurfaceColorMetadata.mapColorTransfer(C.COLOR_TRANSFER_ST2084),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorTransfer.HLG,
            XrSurfaceColorMetadata.mapColorTransfer(C.COLOR_TRANSFER_HLG),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorTransfer.LINEAR,
            XrSurfaceColorMetadata.mapColorTransfer(C.COLOR_TRANSFER_LINEAR),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorTransfer.GAMMA_2_2,
            XrSurfaceColorMetadata.mapColorTransfer(C.COLOR_TRANSFER_GAMMA_2_2),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorTransfer.SRGB,
            XrSurfaceColorMetadata.mapColorTransfer(C.COLOR_TRANSFER_SRGB),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorTransfer.SDR,
            XrSurfaceColorMetadata.mapColorTransfer(C.COLOR_TRANSFER_SDR),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.ColorTransfer.SDR,
            XrSurfaceColorMetadata.mapColorTransfer(Format.NO_VALUE),
        )
    }

    @Test
    fun `extractMaxContentLightLevel parses 25-byte CTA-861_3 and 27-byte prefixed payloads`() {
        // 25-byte CTA-861.3 static metadata Type 1 with MaxCLL = 1000 nits (0x03E8) at bytes 21..22
        val cta25 = ByteArray(25)
        cta25[0] = 0
        cta25[21] = 0xE8.toByte()
        cta25[22] = 0x03.toByte()
        assertEquals(1000, XrSurfaceColorMetadata.extractMaxContentLightLevel(cta25))

        // 27-byte prefixed payload with descriptor_id = 0 at byte 2 and MaxCLL = 4000 nits (0x0FA0) at bytes 23..24
        val cta27 = ByteArray(27)
        cta27[0] = 1
        cta27[1] = 25
        cta27[2] = 0
        cta27[23] = 0xA0.toByte()
        cta27[24] = 0x0F.toByte()
        assertEquals(4000, XrSurfaceColorMetadata.extractMaxContentLightLevel(cta27))
    }

    @Test
    fun `extractMaxContentLightLevel preserves MAX_CONTENT_LIGHT_LEVEL_UNKNOWN when MaxCLL is zero or truncated`() {
        val zeroMaxCll = ByteArray(25)
        assertEquals(
            SurfaceEntity.ContentColorMetadata.MAX_CONTENT_LIGHT_LEVEL_UNKNOWN,
            XrSurfaceColorMetadata.extractMaxContentLightLevel(zeroMaxCll),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.MAX_CONTENT_LIGHT_LEVEL_UNKNOWN,
            XrSurfaceColorMetadata.extractMaxContentLightLevel(ByteArray(10)),
        )
        assertEquals(
            SurfaceEntity.ContentColorMetadata.MAX_CONTENT_LIGHT_LEVEL_UNKNOWN,
            XrSurfaceColorMetadata.extractMaxContentLightLevel(null),
        )
    }

    @Test
    fun `fromColorInfo returns null for null, untagged, or standard SDR ColorInfo`() {
        assertNull(XrSurfaceColorMetadata.fromColorInfo(null))

        val sdrColorInfo = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorTransfer(C.COLOR_TRANSFER_SDR)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .build()
        assertNull(XrSurfaceColorMetadata.fromColorInfo(sdrColorInfo))
    }

    @Test
    fun `fromColorInfo returns populated ContentColorMetadata for HDR10 and HLG`() {
        val cta25 = ByteArray(25).apply {
            this[0] = 0
            this[21] = 0xDC.toByte() // 1500 nits = 0x05DC
            this[22] = 0x05.toByte()
        }
        val hdr10 = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setHdrStaticInfo(cta25)
            .build()
        val mappedHdr10 = XrSurfaceColorMetadata.fromColorInfo(hdr10)
        assertNotNull(mappedHdr10)
        assertEquals(SurfaceEntity.ContentColorMetadata.ColorSpace.BT2020, mappedHdr10!!.colorSpace)
        assertEquals(SurfaceEntity.ContentColorMetadata.ColorTransfer.ST2084, mappedHdr10.colorTransfer)
        assertEquals(SurfaceEntity.ContentColorMetadata.ColorRange.LIMITED, mappedHdr10.colorRange)
        assertEquals(1500, mappedHdr10.maxContentLightLevel)

        val hlg = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .setColorRange(C.COLOR_RANGE_FULL)
            .build()
        val mappedHlg = XrSurfaceColorMetadata.fromColorInfo(hlg)
        assertNotNull(mappedHlg)
        assertEquals(SurfaceEntity.ContentColorMetadata.ColorSpace.BT2020, mappedHlg!!.colorSpace)
        assertEquals(SurfaceEntity.ContentColorMetadata.ColorTransfer.HLG, mappedHlg.colorTransfer)
        assertEquals(SurfaceEntity.ContentColorMetadata.ColorRange.FULL, mappedHlg.colorRange)
        assertEquals(
            SurfaceEntity.ContentColorMetadata.MAX_CONTENT_LIGHT_LEVEL_UNKNOWN,
            mappedHlg.maxContentLightLevel,
        )
    }
}
