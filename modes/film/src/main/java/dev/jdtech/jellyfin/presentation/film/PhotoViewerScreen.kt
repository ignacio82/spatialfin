package dev.jdtech.jellyfin.presentation.film

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.film.presentation.photo.PhotoViewerState
import dev.jdtech.jellyfin.film.presentation.photo.PhotoViewerViewModel
import dev.jdtech.jellyfin.models.SpatialFinPhoto
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Full-screen photo viewer shared by every form factor.
 *
 * Phone and XR drive it with pointer input (pinch to zoom, drag to pan, double-tap
 * to reset); TV drives it with the D-pad, which is why the pager sits inside a
 * focusable key handler instead of relying on swipe alone.
 */
@Composable
fun PhotoViewerScreen(
    photoId: UUID,
    parentId: UUID?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(photoId, parentId) { viewModel.load(photoId, parentId) }

    PhotoViewerLayout(state = state, onBack = onBack, modifier = modifier)
}

@Composable
private fun PhotoViewerLayout(
    state: PhotoViewerState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (state.photos.isEmpty()) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Text(
                    text = state.error?.message ?: "This photo could not be loaded.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            PhotoViewerTopBar(title = "", onBack = onBack)
            return@Box
        }

        val pagerState =
            rememberPagerState(initialPage = state.startIndex.coerceIn(0, state.photos.lastIndex)) {
                state.photos.size
            }
        val scope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }

        // The node is not attached on the first composition in every shell, and an
        // unattached FocusRequester throws — so this is best-effort.
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val target =
                            when (event.key) {
                                Key.DirectionLeft -> pagerState.currentPage - 1
                                Key.DirectionRight -> pagerState.currentPage + 1
                                else -> return@onPreviewKeyEvent false
                            }
                        if (target !in state.photos.indices) return@onPreviewKeyEvent false
                        scope.launch { pagerState.animateScrollToPage(target) }
                        true
                    }
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                state.photos.getOrNull(page)?.let { ZoomablePhoto(photo = it) }
            }
        }

        // getOrNull, not [] — a reload can shrink the list a frame before the pager
        // clamps its current page.
        PhotoViewerTopBar(
            title = state.photos.getOrNull(pagerState.currentPage)?.name.orEmpty(),
            onBack = onBack,
        )

        if (state.photos.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1} / ${state.photos.size}",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ZoomablePhoto(photo: SpatialFinPhoto) {
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(photo.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(photo.id) { mutableFloatStateOf(0f) }

    AsyncImage(
        model = photo.images.primary,
        contentDescription = photo.name,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier.fillMaxSize()
                .pointerInput(photo.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else DOUBLE_TAP_SCALE
                            offsetX = 0f
                            offsetY = 0f
                        }
                    )
                }
                .pointerInput(photo.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            // Back at 1x the pager owns the horizontal drag, so drop any
                            // leftover pan rather than leaving the photo off-centre.
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // Clamp the pan to what the zoom actually reveals so a photo can
                    // never be dragged off-screen and stranded there.
                    val maxPanX = (size.width * (scale - 1f) / 2f).coerceAtLeast(0f)
                    val maxPanY = (size.height * (scale - 1f) / 2f).coerceAtLeast(0f)
                    translationX = offsetX.coerceIn(-maxPanX, maxPanX)
                    translationY = offsetY.coerceIn(-maxPanY, maxPanY)
                },
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PhotoViewerTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
