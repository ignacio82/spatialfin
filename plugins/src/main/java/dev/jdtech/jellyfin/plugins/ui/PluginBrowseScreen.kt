package dev.jdtech.jellyfin.plugins.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.plugins.model.toSpatialFinItem
import dev.jdtech.jellyfin.plugins.repository.PluginContentRepository
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PluginBrowseViewModel @Inject constructor(
    private val contentRepository: PluginContentRepository,
    private val pluginRepository: PluginRepository
) : ViewModel() {
    private val _state = MutableStateFlow(PluginBrowseState())
    val state = _state.asStateFlow()

    fun load(pluginId: String) {
        val plugin = pluginRepository.getInstalledPlugins().find { it.id == pluginId }
        _state.value = _state.value.copy(
            pluginId = pluginId,
            pluginName = plugin?.name ?: "Unknown",
            settings = plugin?.settings ?: emptyList(),
            settingValues = pluginRepository.getPluginSettings(pluginId)
        )
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val items = contentRepository.getPluginHome(pluginId).map { it.toSpatialFinItem() }
            _state.value = _state.value.copy(items = items, isLoading = false)
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val pluginId = _state.value.pluginId ?: return
        val query = _state.value.query.trim()
        if (query.isBlank()) {
            load(pluginId)
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
        load(pluginId)
    }
}

data class PluginBrowseState(
    val pluginId: String? = null,
    val pluginName: String = "",
    val items: List<SpatialFinItem> = emptyList(),
    val query: String = "",
    val settings: List<dev.jdtech.jellyfin.plugins.model.PluginSetting> = emptyList(),
    val settingValues: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false
)

@Composable
fun PluginBrowseScreen(
    pluginId: String,
    onBack: () -> Unit,
    onItemClick: (SpatialFinItem) -> Unit,
    viewModel: PluginBrowseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(pluginId) {
        viewModel.load(pluginId)
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
            onUpdate = viewModel::updateSetting
        )

        if (state.settings.isNotEmpty()) {
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
                    PluginItemCard(item = item, onClick = { 
                        if (item is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem) {
                            try {
                                // Assume BeamPlayerActivity is available via classpath even if not explicitly imported
                                val clazz = Class.forName("dev.jdtech.jellyfin.player.beam.BeamPlayerActivity")
                                val intent = android.content.Intent(context, clazz).apply {
                                    putExtra("universalPluginId", item.universalMediaItem.pluginId)
                                    putExtra("universalItemId", item.universalMediaItem.id)
                                    putExtra("universalVideoUrl", item.universalMediaItem.videoUrl)
                                    putExtra("universalTitle", item.name)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                onItemClick(item)
                            }
                        } else {
                            onItemClick(item) 
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun PluginSettingsPanel(
    settings: List<dev.jdtech.jellyfin.plugins.model.PluginSetting>,
    values: Map<String, String>,
    onUpdate: (String, String) -> Unit
) {
    val visibleSettings = settings.filter { it.variable != null }
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
                    "action" -> {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(setting.name ?: key)
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
