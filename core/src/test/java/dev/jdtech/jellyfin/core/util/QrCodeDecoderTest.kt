package dev.jdtech.jellyfin.core.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeDecoderTest {

    private fun encodeToYuv(text: String, width: Int, height: Int, rowStride: Int = width): ByteArray {
        val bitMatrix: BitMatrix =
            MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
        val data = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Inverted or standard grayscale: 0 for black module, 255 for white
                data[y * rowStride + x] = if (bitMatrix.get(x, y)) 0.toByte() else 255.toByte()
            }
        }
        return data
    }

    @Test
    fun `decode standard orientation QR code successfully`() {
        val payload = "sfcp:http://192.168.1.100:8096|tok_abc123"
        val yuvData = encodeToYuv(payload, 256, 256)

        val result = QrCodeDecoder.decodeYuv(
            yData = yuvData,
            width = 256,
            height = 256,
            rowStride = 256,
            rotationDegrees = 0,
        )

        assertNotNull(result)
        assertEquals(payload, result)
    }

    @Test
    fun `decode 90-degree rotated QR code successfully`() {
        val payload = "sfcp:https://jellyfin.example.com|tok_xyz789"
        val size = 256
        val normalYuv = encodeToYuv(payload, size, size)

        // Rotate raw bytes 90 deg clockwise to simulate camera sensor rotation
        val rotatedSensorYuv = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                rotatedSensorYuv[x * size + (size - 1 - y)] = normalYuv[y * size + x]
            }
        }

        val result = QrCodeDecoder.decodeYuv(
            yData = rotatedSensorYuv,
            width = size,
            height = size,
            rowStride = size,
            rotationDegrees = 90,
        )

        assertNotNull(result)
        assertEquals(payload, result)
    }

    @Test
    fun `decode 270-degree rotated QR code with stride padding successfully`() {
        val payload = "https://spatialfin.app/companion?token=test_token_12345"
        val width = 300
        val height = 300
        val normalYuv = encodeToYuv(payload, width, height)

        // Rotate 270 deg and add row padding
        val rowStride = 350
        val rotatedSensorYuv = ByteArray(rowStride * width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dstX = y
                val dstY = width - 1 - x
                rotatedSensorYuv[dstY * rowStride + dstX] = normalYuv[y * width + x]
            }
        }

        val result = QrCodeDecoder.decodeYuv(
            yData = rotatedSensorYuv,
            width = height,
            height = width,
            rowStride = rowStride,
            rotationDegrees = 270,
        )

        assertNotNull(result)
        assertEquals(payload, result)
    }

    @Test
    fun `blank frame returns null without throwing exception`() {
        val blankData = ByteArray(100 * 100) { 128.toByte() }
        val result = QrCodeDecoder.decodeYuv(
            yData = blankData,
            width = 100,
            height = 100,
            rowStride = 100,
            rotationDegrees = 0,
        )
        assertNull(result)
    }

    @Test
    fun `empty data returns null without throwing exception`() {
        val result = QrCodeDecoder.decodeYuv(
            yData = ByteArray(0),
            width = 0,
            height = 0,
            rowStride = 0,
            rotationDegrees = 0,
        )
        assertNull(result)
    }
}
