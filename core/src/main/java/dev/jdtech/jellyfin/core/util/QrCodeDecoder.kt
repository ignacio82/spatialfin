package dev.jdtech.jellyfin.core.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import timber.log.Timber

/**
 * Pure Kotlin/Java QR code decoder backed by ZXing.
 *
 * Provides an offline, zero-external-dependency fallback and parallel scanning engine
 * for CameraX frames. Decodes grayscale YUV frames directly without requiring Google
 * Play Services or ML Kit Dynamite modules, preventing crashes and scanner
 * unavailability across devices.
 */
object QrCodeDecoder {
    private val qrReader = QRCodeReader()
    private val hints =
        mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )

    /**
     * Attempts to decode a QR code from raw YUV (Y-plane) luminance bytes.
     *
     * @param yData The luminance byte array (from YUV_420_888 plane 0).
     * @param width Frame width in pixels.
     * @param height Frame height in pixels.
     * @param rowStride Row stride of the Y plane (in bytes).
     * @param rotationDegrees Sensor rotation degrees (0, 90, 180, 270).
     * @return Decoded text content or null if no valid QR code was detected.
     */
    @Synchronized
    fun decodeYuv(
        yData: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        rotationDegrees: Int = 0,
    ): String? {
        if (yData.isEmpty() || width <= 0 || height <= 0) return null

        val (rotatedData, finalWidth, finalHeight) =
            when (rotationDegrees) {
                90 -> Triple(rotateYuv90(yData, width, height, rowStride), height, width)
                180 -> Triple(rotateYuv180(yData, width, height, rowStride), width, height)
                270 -> Triple(rotateYuv270(yData, width, height, rowStride), height, width)
                else -> {
                    if (rowStride == width) {
                        Triple(yData, width, height)
                    } else {
                        Triple(compactYuv(yData, width, height, rowStride), width, height)
                    }
                }
            }

        val source =
            try {
                PlanarYUVLuminanceSource(
                    rotatedData,
                    finalWidth,
                    finalHeight,
                    0,
                    0,
                    finalWidth,
                    finalHeight,
                    false,
                )
            } catch (e: Exception) {
                Timber.w(e, "QR: Failed to construct PlanarYUVLuminanceSource")
                return null
            }

        // Try primary HybridBinarizer (standard for high quality / modern frames)
        val hybridBitmap = BinaryBitmap(HybridBinarizer(source))
        try {
            val result = qrReader.decode(hybridBitmap, hints)
            return result.text
        } catch (_: NotFoundException) {
            // Expected when no QR is present in frame
        } catch (e: Exception) {
            Timber.v(e, "QR: HybridBinarizer decode failed")
        } finally {
            qrReader.reset()
        }

        // Fallback: GlobalHistogramBinarizer (better for low contrast or uneven lighting)
        val globalBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
        try {
            val result = qrReader.decode(globalBitmap, hints)
            return result.text
        } catch (_: NotFoundException) {
            // Expected when no QR is present in frame
        } catch (e: Exception) {
            Timber.v(e, "QR: GlobalHistogramBinarizer decode failed")
        } finally {
            qrReader.reset()
        }

        return null
    }

    private fun compactYuv(data: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
        val output = ByteArray(width * height)
        val copyLength = minOf(width, rowStride)
        for (y in 0 until height) {
            val srcOffset = y * rowStride
            if (srcOffset + copyLength <= data.size) {
                System.arraycopy(data, srcOffset, output, y * width, copyLength)
            }
        }
        return output
    }

    private fun rotateYuv90(data: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
        val output = ByteArray(width * height)
        for (y in 0 until height) {
            val srcRow = y * rowStride
            for (x in 0 until width) {
                if (srcRow + x < data.size) {
                    output[x * height + (height - 1 - y)] = data[srcRow + x]
                }
            }
        }
        return output
    }

    private fun rotateYuv180(data: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
        val output = ByteArray(width * height)
        for (y in 0 until height) {
            val srcRow = y * rowStride
            val dstRow = (height - 1 - y) * width
            for (x in 0 until width) {
                if (srcRow + x < data.size) {
                    output[dstRow + (width - 1 - x)] = data[srcRow + x]
                }
            }
        }
        return output
    }

    private fun rotateYuv270(data: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
        val output = ByteArray(width * height)
        for (y in 0 until height) {
            val srcRow = y * rowStride
            for (x in 0 until width) {
                if (srcRow + x < data.size) {
                    output[(width - 1 - x) * height + y] = data[srcRow + x]
                }
            }
        }
        return output
    }
}
