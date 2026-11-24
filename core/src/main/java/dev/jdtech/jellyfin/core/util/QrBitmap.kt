package dev.jdtech.jellyfin.core.util

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/**
 * Shared QR encoder across `:setup`, `:modes:music`, `:shell:tv`, and `:app:unified`.
 * Returns null on failure.
 */
@Composable
fun rememberQrBitmap(content: String, size: Int = 720): ImageBitmap? =
    remember(content, size) { generateQrBitmap(content, size)?.asImageBitmap() }

fun generateQrBitmap(content: String, size: Int = 720): Bitmap? =
    runCatching {
        MultiFormatWriter()
            .encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            .toBitmap()
    }.getOrNull()

private fun BitMatrix.toBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
