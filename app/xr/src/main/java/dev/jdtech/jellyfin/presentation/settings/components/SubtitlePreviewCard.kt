package dev.jdtech.jellyfin.presentation.settings.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.player.xr.LibassRenderer
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.presentation.theme.spacings
import java.io.File

/**
 * Live subtitle preview rendered against the same pipeline the player uses.
 *
 * Goal: let users tweak xrSubtitleSize / libassSubtitleUsage without having to
 * start playback and scrub until a subtitle appears on-screen. If libass is
 * available we render a tiny sample .ass file through the native renderer so
 * size, outline, and positioning match playback exactly. Otherwise we fall back
 * to a Compose-rendered approximation using the user's preferred size.
 */
@Composable
fun SubtitlePreviewCard(
    appPreferences: AppPreferences,
    modifier: Modifier = Modifier,
) {
    val subtitleSizeSp = appPreferences.getValue(appPreferences.xrSubtitleSize)
    val libassUsage = appPreferences.getValue(appPreferences.libassSubtitleUsage)
    val libassAvailable = remember { runCatching { LibassRenderer.isAvailable() }.getOrDefault(false) }
    val useLibass = libassAvailable && libassUsage != "never"

    Column {
        Text(
            text = "Preview",
            modifier = Modifier.padding(start = MaterialTheme.spacings.medium),
            style = MaterialTheme.typography.headlineSmall,
        )
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.padding(MaterialTheme.spacings.small),
        )
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacings.medium)) {
                // 16:9 "video" surface so users see how subtitles sit against a frame.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D1117)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (useLibass) {
                        LibassPreviewLayer(
                            subtitleSizeSp = subtitleSizeSp,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        FallbackPreviewText(
                            subtitleSizeSp = subtitleSizeSp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = MaterialTheme.spacings.medium,
                                    vertical = MaterialTheme.spacings.medium,
                                ),
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier.padding(MaterialTheme.spacings.small),
                )
                Text(
                    text = if (useLibass) {
                        "Rendered with libass (usage: $libassUsage, size: ${subtitleSizeSp}sp)"
                    } else {
                        "Rendered with Compose fallback (libass disabled, size: ${subtitleSizeSp}sp)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibassPreviewLayer(
    subtitleSizeSp: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Re-keyed whenever size changes so the renderer resizes and re-renders.
    val width = 1280
    val height = 720

    DisposableEffect(subtitleSizeSp) {
        val renderer = LibassRenderer(width, height)
        renderer.init()
        // Scale the libass PlayResY such that "Fontsize: 72" maps to the user's
        // preferred xrSubtitleSize. PlayResY=1080 is the libass convention.
        // Fontsize is used literally below so the script must be regenerated per size.
        val assFile = File(context.cacheDir, "subtitle_preview.ass")
        assFile.writeText(buildSampleAss(subtitleSizeSp))
        renderer.loadAssFile(assFile.absolutePath)

        var disposed = false
        val thread = Thread({
            var t = 0L
            while (!disposed) {
                val result = renderer.renderFrame(t)
                if (result.hasContent && result.bitmap != null) {
                    // Clone onto an immutable bitmap snapshot so Compose is not drawing the
                    // renderer's reusable buffer while the next frame writes over it.
                    val snapshot = result.bitmap!!.copy(Bitmap.Config.ARGB_8888, false)
                    bitmap = snapshot
                }
                t += 120L
                if (t > 5000L) t = 0L
                try { Thread.sleep(120L) } catch (_: InterruptedException) { break }
            }
        }, "subtitle-preview-tick")
        thread.isDaemon = true
        thread.start()

        onDispose {
            disposed = true
            thread.interrupt()
            renderer.destroy()
            bitmap = null
        }
    }

    Box(modifier = modifier) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } ?: FallbackPreviewText(
            subtitleSizeSp = subtitleSizeSp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
private fun FallbackPreviewText(
    subtitleSizeSp: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "The quick brown fox jumps over the lazy dog.",
        color = Color.White,
        fontSize = subtitleSizeSp.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

private fun buildSampleAss(fontSize: Int): String {
    // Minimal ASS script pinned at an always-on dialogue event so the preview
    // shows a subtitle at any timestamp. PlayResY=1080 matches the XR subtitle
    // render surface so sizing behaves identically to playback.
    return """
[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Roboto,$fontSize,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,2,1,2,30,30,40,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:30.00,Default,,0,0,0,,The quick brown fox jumps over the lazy dog.
""".trimIndent()
}
