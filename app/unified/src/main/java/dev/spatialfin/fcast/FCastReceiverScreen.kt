package dev.spatialfin.fcast

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.jdtech.jellyfin.core.R as CoreR
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.spatialfin.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FCastReceiverScreen(
    player: ExoPlayer,
    title: String?,
    thumbnailUrl: String?,
    audioInfoLine: String?,
    libassBitmap: Bitmap?,
    isAudioOnly: Boolean,
    onStop: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    
    // Auto-hide controls after 5 seconds if not audio-only
    LaunchedEffect(showControls, isAudioOnly) {
        if (showControls && !isAudioOnly) {
            delay(5000L)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isAudioOnly) {
                    showControls = !showControls
                }
            }
    ) {
        // 1. Video Player Surface
        if (!isAudioOnly) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Libass Subtitle Overlay
        if (libassBitmap != null && !isAudioOnly) {
            Image(
                bitmap = libassBitmap.asImageBitmap(),
                contentDescription = "Subtitles",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }

        // 3. Cinematic Blurred Background & Controls
        AnimatedVisibility(
            visible = showControls || isAudioOnly,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Ambient Cinematic Background (Visible only when controls are up or Audio Only)
                if (!thumbnailUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(80.dp)
                            .alpha(if (isAudioOnly) 0.6f else 0.4f)
                    )
                }

                // Top & Bottom Gradients for text legibility
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.7f),
                                0.2f to Color.Transparent,
                                0.6f to Color.Transparent,
                                1.0f to Color.Black.copy(alpha = 0.8f)
                            )
                        )
                )

                // Top Header (SpatialFin Receiver)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onStop) {
                            Icon(
                                painter = painterResource(id = CoreR.drawable.ic_arrow_left),
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Icon(
                            painter = painterResource(id = dev.spatialfin.R.drawable.ic_cast_connected),
                            contentDescription = "Cast Icon",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp).padding(start = 8.dp)
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = "SpatialFin Receiver",
                                color = Color(0xFF9ADBFF),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Casting",
                                color = Color(0xCCBDC8D0),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        IconButton(onClick = {
                            androidx.media3.ui.TrackSelectionDialogBuilder(
                                context, "Select Subtitles", player, androidx.media3.common.C.TRACK_TYPE_TEXT
                            ).build().show()
                        }) {
                            Icon(
                                painter = painterResource(id = dev.spatialfin.R.drawable.ic_subtitles),
                                contentDescription = "Subtitles",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.size(16.dp))
                        IconButton(onClick = {
                            androidx.media3.ui.TrackSelectionDialogBuilder(
                                context, "Select Audio", player, androidx.media3.common.C.TRACK_TYPE_AUDIO
                            ).build().show()
                        }) {
                            Icon(
                                painter = painterResource(id = dev.spatialfin.R.drawable.ic_audiotrack),
                                contentDescription = "Audio",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.size(16.dp))
                        IconButton(onClick = {
                            androidx.media3.ui.TrackSelectionDialogBuilder(
                                context, "Select Quality", player, androidx.media3.common.C.TRACK_TYPE_VIDEO
                            ).build().show()
                        }) {
                            Icon(
                                painter = painterResource(id = dev.spatialfin.R.drawable.ic_settings),
                                contentDescription = "Quality",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                // Controls & Metadata at Bottom
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 64.dp)
                ) {
                    // Title and Audio Info
                    Text(
                        text = title ?: "FCast Media",
                        color = Color.White,
                        fontSize = 45.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (!audioInfoLine.isNullOrEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0x33FFFFFF),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(
                                text = audioInfoLine,
                                color = Color(0xFFBDC8D0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Glass Panel
                    Surface(
                        color = Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp)
                        ) {
                            // Audio-only Stop Button placeholder or full controls
                            if (isAudioOnly) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF9ADBFF),
                                        modifier = Modifier.clickable { onStop() }
                                    ) {
                                        Text(
                                            text = "Stop Audio",
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                                        )
                                    }
                                }
                            } else {
                                // Exoplayer controls require state reading which is complex in pure Compose
                                // We can integrate the AndroidView for PlayerControlView here, or just show minimal
                                // We'll wrap the standard ExoPlayer PlayerControlView inside the glass panel
                                // so we don't have to rewrite the scrubbing state logic.
                                AndroidView(
                                    factory = { context ->
                                        val view = View.inflate(context, R.layout.fcast_glass_control_wrapper, null) as androidx.media3.ui.PlayerControlView
                                        view.player = player
                                        view.showTimeoutMs = 0 // handled by Compose
                                        
                                        // Media3 ignores exo_play and exo_pause automatically, so we wire them manually
                                        val playBtn = view.findViewById<View>(R.id.fcast_btn_play)
                                        val pauseBtn = view.findViewById<View>(R.id.fcast_btn_pause)
                                        
                                        playBtn?.setOnClickListener { player?.play() }
                                        pauseBtn?.setOnClickListener { player?.pause() }
                                        
                                        val updateButtons = { isPlaying: Boolean ->
                                            if (isPlaying) {
                                                playBtn?.visibility = View.GONE
                                                pauseBtn?.visibility = View.VISIBLE
                                            } else {
                                                playBtn?.visibility = View.VISIBLE
                                                pauseBtn?.visibility = View.GONE
                                            }
                                        }
                                        
                                        player?.addListener(object : androidx.media3.common.Player.Listener {
                                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                                updateButtons(isPlaying)
                                            }
                                        })
                                        updateButtons(player?.isPlaying == true)
                                        
                                        view
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
