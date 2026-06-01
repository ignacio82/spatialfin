package dev.jdtech.jellyfin.plugins.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.plugins.model.ResolvedVideoUrl
import dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
import dev.jdtech.jellyfin.plugins.model.PluginSetting
import dev.jdtech.jellyfin.plugins.model.toSpatialFinItem
import dev.jdtech.jellyfin.plugins.repository.PluginContentRepository
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import javax.inject.Inject

@HiltViewModel
class PluginBrowseViewModel @Inject constructor(
    private val contentRepository: PluginContentRepository,
    private val pluginRepository: PluginRepository
) : ViewModel() {
    private val _state = MutableStateFlow(PluginBrowseState())
    val state = _state.asStateFlow()

    fun load(pluginId: String, rowId: String? = null) {
        val plugin = pluginRepository.getInstalledPlugins().find { it.id == pluginId }
        val row = rowId?.let { id -> plugin?.let { pluginRepository.getPluginHomeRows(it).find { row -> row.id == id } } }
        _state.value = _state.value.copy(
            pluginId = pluginId,
            rowId = rowId,
            presentation = row?.presentation ?: plugin?.presentation,
            pluginName = row?.name ?: plugin?.name ?: "Unknown",
            settings = plugin?.settings ?: emptyList(),
            settingValues = pluginRepository.getPluginSettings(pluginId)
        )
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val items = contentRepository.getPluginHome(pluginId, rowId).map { it.toSpatialFinItem() }
            _state.value = _state.value.copy(items = items, isLoading = false)
        }
    }

    fun loadMoreIfNeeded(index: Int) {
        val items = _state.value.items
        if (_state.value.isLoadingMore || items.isEmpty()) return
        val prefetchIndex = (items.lastIndex - 2).coerceAtLeast(0)
        if (index < prefetchIndex) return
        val lastUniversal = items.lastOrNull() as? UniversalSpatialFinItem ?: return
        val pluginId = _state.value.pluginId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true)
            val more = contentRepository.getPager(pluginId, lastUniversal.universalMediaItem.videoUrl)
                .map { it.toSpatialFinItem() }
            val existingIds = _state.value.items.map { it.id }.toSet()
            val uniqueMore = more.filterNot { it.id in existingIds }
            _state.value = _state.value.copy(
                items = _state.value.items + uniqueMore,
                isLoadingMore = false
            )
        }
    }

    suspend fun resolvePlayable(item: UniversalSpatialFinItem): ResolvedVideoUrl? {
        return contentRepository.getVideoUrl(
            pluginId = item.universalMediaItem.pluginId,
            videoUrl = item.universalMediaItem.videoUrl,
            isLive = item.universalMediaItem.isLive
        )
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val pluginId = _state.value.pluginId ?: return
        val query = _state.value.query.trim()
        if (query.isBlank()) {
            load(pluginId, _state.value.rowId)
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val items = contentRepository.searchPlugin(pluginId, query).map { it.toSpatialFinItem() }
            _state.value = _state.value.copy(items = items, isLoading = false)
        }
    }

    fun updateSetting(key: String, value: String) {
        val pluginId = _state.value.pluginId ?: return
        pluginRepository.updatePluginSetting(pluginId, key, value)
        _state.value = _state.value.copy(settingValues = pluginRepository.getPluginSettings(pluginId))
        load(pluginId, _state.value.rowId)
    }

    fun saveAuthSettings(settings: Map<String, String>) {
        val pluginId = _state.value.pluginId ?: return
        settings.forEach { (key, value) ->
            pluginRepository.updatePluginSetting(pluginId, key, value)
        }
        _state.value = _state.value.copy(settingValues = pluginRepository.getPluginSettings(pluginId))
        load(pluginId, _state.value.rowId)
    }

    fun clearAuthSettings(cookieKey: String, loggedInKey: String) {
        saveAuthSettings(
            mapOf(
                cookieKey to "",
                loggedInKey to "false"
            )
        )
    }
}

data class PluginBrowseState(
    val pluginId: String? = null,
    val rowId: String? = null,
    val pluginName: String = "",
    val presentation: String? = null,
    val items: List<SpatialFinItem> = emptyList(),
    val query: String = "",
    val settings: List<dev.jdtech.jellyfin.plugins.model.PluginSetting> = emptyList(),
    val settingValues: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false
)

@Composable
fun PluginBrowseScreen(
    pluginId: String,
    rowId: String? = null,
    onBack: () -> Unit,
    onItemClick: (SpatialFinItem) -> Unit,
    viewModel: PluginBrowseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var authSetting by remember { mutableStateOf<PluginSetting?>(null) }

    LaunchedEffect(pluginId, rowId) {
        viewModel.load(pluginId, rowId)
    }

    val verticalFeed = state.presentation == "verticalVideoFeed"
    BackHandler(enabled = verticalFeed) {
        // Vertical video feeds use swipe gestures heavily. Use the explicit overlay back button
        // so an accidental system back gesture does not exit while paging between videos.
    }

    if (verticalFeed) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                VerticalVideoFeed(
                    items = state.items.filterIsInstance<UniversalSpatialFinItem>(),
                    isLoadingMore = state.isLoadingMore,
                    onResolve = viewModel::resolvePlayable,
                    onPageChanged = viewModel::loadMoreIfNeeded,
                    onOpen = { item -> onItemClick(item) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = state.pluginName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = state.pluginName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.setQuery(it) },
            label = { Text("Search ${state.pluginName}") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            trailingIcon = {
                IconButton(onClick = { viewModel.search() }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search")
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        PluginSettingsPanel(
            settings = state.settings,
            values = state.settingValues,
            onUpdate = viewModel::updateSetting,
            onAuthRequest = { setting -> authSetting = setting },
            onClearAuth = { cookieKey, loggedInKey ->
                viewModel.clearAuthSettings(cookieKey, loggedInKey)
            }
        )

        if (state.settings.any { it.variable != null && it.type?.lowercase() != "hidden" }) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(state.items.size) { index ->
                    val item = state.items[index]
                    PluginItemCard(item = item, onClick = { onItemClick(item) })
                }
            }
        }
    }

    authSetting?.let { setting ->
        PluginAuthDialog(
            setting = setting,
            onDismiss = { authSetting = null },
            onDone = { cookieKey, loggedInKey, cookies ->
                viewModel.saveAuthSettings(
                    mapOf(
                        cookieKey to cookies,
                        loggedInKey to cookies.isNotBlank().toString()
                    )
                )
                authSetting = null
            }
        )
    }
}

@Composable
private fun VerticalVideoFeed(
    items: List<UniversalSpatialFinItem>,
    isLoadingMore: Boolean,
    onResolve: suspend (UniversalSpatialFinItem) -> ResolvedVideoUrl?,
    onPageChanged: (Int) -> Unit,
    onOpen: (UniversalSpatialFinItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No videos")
        }
        return
    }

    var currentPage by remember { mutableIntStateOf(0) }
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(items.size, isLoadingMore) {
        if (!isLoadingMore && currentPage > items.lastIndex) {
            currentPage = items.lastIndex.coerceAtLeast(0)
            dragOffset.snapTo(0f)
        }
    }

    LaunchedEffect(currentPage, items.size) {
        onPageChanged(currentPage.coerceAtMost(items.lastIndex))
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().background(Color.Black)
    ) {
        val pageHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val pageCount = items.size + if (isLoadingMore) 1 else 0
        val canGoPrevious = currentPage > 0
        val canGoNext = currentPage < pageCount - 1

        fun settleDrag() {
            val offset = dragOffset.value
            val threshold = pageHeight * 0.22f
            scope.launch {
                when {
                    offset <= -threshold && canGoNext -> {
                        dragOffset.animateTo(-pageHeight, tween(durationMillis = 170))
                        currentPage += 1
                        dragOffset.snapTo(0f)
                    }
                    offset >= threshold && canGoPrevious -> {
                        dragOffset.animateTo(pageHeight, tween(durationMillis = 170))
                        currentPage -= 1
                        dragOffset.snapTo(0f)
                    }
                    else -> {
                        dragOffset.animateTo(0f, tween(durationMillis = 130))
                    }
                }
            }
        }

        listOf(currentPage - 1, currentPage, currentPage + 1)
            .filter { it in 0 until pageCount }
            .forEach { page ->
                val baseOffset = when {
                    page < currentPage -> -pageHeight
                    page > currentPage -> pageHeight
                    else -> 0f
                }
                val pageTranslation = baseOffset + dragOffset.value
                val pageOffset = (pageTranslation / pageHeight).absoluteValue.coerceIn(0f, 1f)
                val pageScale = 0.94f + ((1f - pageOffset) * 0.06f)
                val pageAlpha = 0.62f + ((1f - pageOffset) * 0.38f)

                val pageModifier = Modifier.graphicsLayer {
                        translationY = pageTranslation
                        scaleX = pageScale
                        scaleY = pageScale
                        alpha = pageAlpha
                    }
                if (page in items.indices) {
                    VerticalVideoPage(
                        item = items[page],
                        active = page == currentPage,
                        onResolve = onResolve,
                        showLoadingMore = isLoadingMore && page == items.lastIndex,
                        modifier = pageModifier
                    )
                } else {
                    VerticalFeedLoadingPage(modifier = pageModifier)
                }
            }

        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(pageCount, currentPage, pageHeight) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            scope.launch { dragOffset.stop() }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val minOffset = if (canGoNext) -pageHeight else 0f
                            val maxOffset = if (canGoPrevious) pageHeight else 0f
                            scope.launch {
                                dragOffset.snapTo(
                                    (dragOffset.value + dragAmount).coerceIn(minOffset, maxOffset)
                                )
                            }
                        },
                        onDragEnd = { settleDrag() },
                        onDragCancel = { settleDrag() }
                    )
                }
        )
    }
}

@Composable
private fun VerticalFeedLoadingPage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading more videos",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun VerticalVideoPage(
    item: UniversalSpatialFinItem,
    active: Boolean,
    onResolve: suspend (UniversalSpatialFinItem) -> ResolvedVideoUrl?,
    showLoadingMore: Boolean,
    modifier: Modifier = Modifier
) {
    var resolvedUrl by remember(item.id) { mutableStateOf<String?>(null) }
    var resolveFailed by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, active) {
        if (active && resolvedUrl == null && !resolveFailed) {
            val resolved = onResolve(item)
            resolvedUrl = resolved?.url
            resolveFailed = resolved?.url.isNullOrBlank()
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val videoUrl = resolvedUrl
        if (videoUrl != null && active) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            player.start()
                        }
                    }
                },
                update = { videoView ->
                    val current = videoView.tag as? String
                    if (current != videoUrl) {
                        videoView.tag = videoUrl
                        videoView.setVideoURI(Uri.parse(videoUrl))
                    }
                    if (!videoView.isPlaying) {
                        videoView.start()
                    }
                },
                onRelease = { videoView ->
                    videoView.stopPlayback()
                }
            )
        } else {
            AsyncImage(
                model = item.images.backdrop ?: item.images.primary,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            if (!resolveFailed) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            item.universalMediaItem.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(text = author, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            }
            item.overview.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 3
                )
            }
            if (showLoadingMore) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun PluginSettingsPanel(
    settings: List<PluginSetting>,
    values: Map<String, String>,
    onUpdate: (String, String) -> Unit,
    onAuthRequest: (PluginSetting) -> Unit,
    onClearAuth: (String, String) -> Unit
) {
    val visibleSettings = settings.filter { it.variable != null && it.type?.lowercase() != "hidden" }
    if (visibleSettings.isEmpty()) return

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Plugin Options",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            visibleSettings.forEach { setting ->
                val key = setting.variable ?: return@forEach
                when (setting.type?.lowercase()) {
                    "boolean" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(setting.name ?: key, style = MaterialTheme.typography.bodyLarge)
                                setting.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = values[key].equals("true", ignoreCase = true),
                                onCheckedChange = { checked -> onUpdate(key, checked.toString()) }
                            )
                        }
                    }
                    "auth" -> {
                        AuthSettingRow(
                            setting = setting,
                            key = key,
                            values = values,
                            onAuthRequest = onAuthRequest,
                            onClearAuth = onClearAuth
                        )
                    }
                    "action" -> {
                        if (setting.loginUrl() != null) {
                            AuthSettingRow(
                                setting = setting,
                                key = key,
                                values = values,
                                onAuthRequest = onAuthRequest,
                                onClearAuth = onClearAuth
                            )
                        } else {
                            OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(setting.name ?: key)
                            }
                        }
                    }
                    else -> {
                        Text(setting.name ?: key, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthSettingRow(
    setting: PluginSetting,
    key: String,
    values: Map<String, String>,
    onAuthRequest: (PluginSetting) -> Unit,
    onClearAuth: (String, String) -> Unit
) {
    val cookieKey = setting.cookieSettingKey()
    val loggedInKey = setting.loggedInSettingKey()
    val signedIn = values[loggedInKey].equals("true", ignoreCase = true) &&
        values[cookieKey].orEmpty().isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(setting.name ?: key, style = MaterialTheme.typography.bodyLarge)
            setting.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (signedIn) "Signed in" else "Not signed in",
                style = MaterialTheme.typography.labelMedium,
                color = if (signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onAuthRequest(setting) },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (signedIn) "Sign in again" else "Sign in")
            }
            if (signedIn) {
                TextButton(
                    onClick = { onClearAuth(cookieKey, loggedInKey) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sign out")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PluginAuthDialog(
    setting: PluginSetting,
    onDismiss: () -> Unit,
    onDone: (cookieKey: String, loggedInKey: String, cookies: String) -> Unit
) {
    val loginUrl = setting.loginUrl() ?: return
    val cookieUrls = setting.cookieUrls().ifEmpty { listOf(loginUrl) }
    val cookieKey = setting.cookieSettingKey()
    val loggedInKey = setting.loggedInSettingKey()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = setting.name ?: "Sign in",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            CookieManager.getInstance().flush()
                            onDone(cookieKey, loggedInKey, collectCookieHeader(cookieUrls))
                        }
                    ) {
                        Text("Done")
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl(loginUrl)
                        }
                    }
                )
            }
        }
    }
}

private fun PluginSetting.optionsObject(): JsonObject? = options as? JsonObject

private fun PluginSetting.optionString(key: String): String? =
    optionsObject()?.get(key)?.jsonPrimitive?.contentOrNull

private fun PluginSetting.loginUrl(): String? =
    optionString("loginUrl")

private fun PluginSetting.cookieSettingKey(): String =
    optionString("cookieSetting") ?: "${variable ?: "plugin"}Cookies"

private fun PluginSetting.loggedInSettingKey(): String =
    optionString("loggedInSetting") ?: "${variable ?: "plugin"}LoggedIn"

private fun PluginSetting.cookieUrls(): List<String> {
    val urls = optionsObject()?.get("cookieUrls")
        ?: optionsObject()?.get("cookieDomains")
        ?: return emptyList()
    return when (urls) {
        is JsonArray -> urls.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        else -> urls.jsonPrimitive.contentOrNull?.let(::listOf).orEmpty()
    }
}

private fun collectCookieHeader(urls: List<String>): String {
    val cookieManager = CookieManager.getInstance()
    val merged = linkedMapOf<String, String>()
    urls.forEach { url ->
        cookieManager.getCookie(url)
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.contains("=") }
            ?.forEach { cookie ->
                val name = cookie.substringBefore("=").trim()
                if (name.isNotBlank()) merged[name] = cookie
            }
    }
    return merged.values.joinToString("; ")
}

@Composable
fun PluginItemCard(item: SpatialFinItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box {
            val imageModel = item.images.primary ?: item.images.backdrop
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.padding(8.dp),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}
