package dev.jdtech.jellyfin.player.xr

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.xr.compose.platform.LocalSession
import androidx.xr.scenecore.scene
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.util.UUID

@AndroidEntryPoint
class MultitaskPlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    private var mediaSession: MediaSession? = null

    companion object {
        fun createIntent(
            context: Context,
            itemId: UUID,
            itemKind: String,
            startFromBeginning: Boolean = false,
            mediaSourceIndex: Int? = null,
            maxBitrate: Long? = null,
        ): Intent {
            return Intent(context, MultitaskPlayerActivity::class.java).apply {
                putExtra("itemId", itemId.toString())
                putExtra("itemKind", itemKind)
                putExtra("startFromBeginning", startFromBeginning)
                mediaSourceIndex?.let { putExtra("mediaSourceIndex", it) }
                maxBitrate?.let { putExtra("maxBitrate", it) }
            }
        }
        
        fun createIntentForLocalMedia(
            context: Context,
            mediaStoreId: Long,
            startFromBeginning: Boolean = false,
        ): Intent {
            return Intent(context, MultitaskPlayerActivity::class.java).apply {
                putExtra("localMediaId", mediaStoreId)
                putExtra("startFromBeginning", startFromBeginning)
            }
        }
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemIdString = intent.extras?.getString("itemId")
        val localMediaId = intent.extras?.getLong("localMediaId")?.takeIf { it > 0L }
        
        if (itemIdString == null && localMediaId == null) {
            finish()
            return
        }
        
        val itemId = itemIdString?.let(UUID::fromString)
        val itemKind = intent.extras?.getString("itemKind") ?: ""
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")
        val mediaSourceIndex = if (intent.hasExtra("mediaSourceIndex")) intent.getIntExtra("mediaSourceIndex", -1).takeIf { it >= 0 } else null
        val maxBitrate = if (intent.hasExtra("maxBitrate")) intent.getLongExtra("maxBitrate", 0L).takeIf { it > 0L } else null

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setSeekBackIncrementMs(viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekBackInc))
            .setSeekForwardIncrementMs(viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekForwardInc))
            .setPauseAtEndOfMediaItems(true)
            .build()

        viewModel.replacePlayer(player)
        
        if (localMediaId != null) {
            viewModel.initializeLocalPlayer(localMediaId, startFromBeginning)
        } else if (itemId != null) {
            viewModel.initializePlayer(
                itemId = itemId,
                itemKind = itemKind,
                startFromBeginning = startFromBeginning,
                mediaSourceIndex = mediaSourceIndex,
                maxBitrate = maxBitrate,
            )
        }

        updatePipParams()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val session = LocalSession.current
                LaunchedEffect(session) {
                    try {
                        session?.scene?.requestHomeSpaceMode()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }

                var isPipMode by remember { mutableStateOf(false) }
                
                DisposableEffect(Unit) {
                    val listener = object : androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
                        override fun accept(t: androidx.core.app.PictureInPictureModeChangedInfo) {
                            isPipMode = t.isInPictureInPictureMode
                        }
                    }
                    addOnPictureInPictureModeChangedListener(listener)
                    onDispose {
                        removeOnPictureInPictureModeChangedListener(listener)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!isPipMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        painterResource(CoreR.drawable.ic_arrow_left),
                                        contentDescription = "Back",
                                        tint = Color.White
                                    )
                                }
                                
                                val state by viewModel.uiState.collectAsStateWithLifecycle()
                                Text(
                                    text = state.currentItemTitle,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    IconButton(onClick = { 
                                        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
                                    }) {
                                        Icon(
                                            painterResource(CoreR.drawable.ic_picture_in_picture),
                                            contentDescription = "PiP",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        val context = LocalContext.current
                        val playerView = remember {
                            PlayerView(context).apply {
                                this.player = player
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                useController = true
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        }

                        DisposableEffect(Unit) {
                            onDispose {
                                playerView.player = null
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                factory = { playerView },
                                modifier = Modifier.fillMaxSize(),
                                update = { view ->
                                    view.useController = !isPipMode
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val paramsBuilder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                paramsBuilder.setAutoEnterEnabled(true)
            }
            setPictureInPictureParams(paramsBuilder.build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }
    
    override fun onStart() {
        super.onStart()
        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, viewModel.player).build()
    }

    override fun onResume() {
        super.onResume()
        viewModel.player.playWhenReady = viewModel.playWhenReady
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInPictureInPictureMode) {
                return
            }
        }
        viewModel.updatePlaybackProgress()
    }

    override fun onStop() {
        super.onStop()
        viewModel.playWhenReady = viewModel.player.playWhenReady
        viewModel.player.playWhenReady = false
        mediaSession?.release()
        mediaSession = null
    }
}
