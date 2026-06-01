package dev.jdtech.jellyfin.plugins.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import dev.jdtech.jellyfin.plugins.model.PluginHomeRow
import dev.jdtech.jellyfin.plugins.model.PluginHomeRowTemplate
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class JellyfinHomeRowUi(
    val key: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)

data class PluginHomeRowUi(
    val pluginId: String,
    val row: PluginHomeRow,
    val enabled: Boolean,
    val canDelete: Boolean = false
)

@HiltViewModel
class PluginSettingsViewModel @Inject constructor(
    private val repository: PluginRepository,
    private val sharedPreferences: SharedPreferences
) : ViewModel() {
    private val _plugins = MutableStateFlow<List<PluginConfig>>(emptyList())
    val plugins = _plugins.asStateFlow()

    private val _jellyfinRows = MutableStateFlow<List<JellyfinHomeRowUi>>(emptyList())
    val jellyfinRows = _jellyfinRows.asStateFlow()

    private val _pluginRows = MutableStateFlow<Map<String, List<PluginHomeRowUi>>>(emptyMap())
    val pluginRows = _pluginRows.asStateFlow()

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling = _isInstalling.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val installedPlugins = repository.getInstalledPlugins()
        _plugins.value = installedPlugins
        _jellyfinRows.value = listOf(
            JellyfinHomeRowUi(
                key = "continue",
                name = "Continue Watching",
                description = "Resume movies and episodes you already started.",
                enabled = sharedPreferences.getBoolean(HOME_CONTINUE_WATCHING, true)
            ),
            JellyfinHomeRowUi(
                key = "nextUp",
                name = "Next Up",
                description = "Continue watching shows from the next unwatched episode.",
                enabled = sharedPreferences.getBoolean(HOME_NEXT_UP, true)
            ),
            JellyfinHomeRowUi(
                key = "suggestions",
                name = "Suggestions",
                description = "Recommended Jellyfin titles at the top of Home.",
                enabled = sharedPreferences.getBoolean(HOME_SUGGESTIONS, true)
            ),
            JellyfinHomeRowUi(
                key = "latest",
                name = "Latest Media",
                description = "Recently added items from your Jellyfin libraries.",
                enabled = sharedPreferences.getBoolean(HOME_LATEST, true)
            )
        )
        _pluginRows.value = installedPlugins.associate { plugin ->
            val pluginId = plugin.id.orEmpty()
            val rows = repository.getPluginHomeRows(plugin).map { row ->
                PluginHomeRowUi(
                    pluginId = pluginId,
                    row = row,
                    enabled = if (pluginId.isBlank()) {
                        row.defaultEnabled
                    } else {
                        repository.isPluginHomeRowEnabled(pluginId, row.id, row.defaultEnabled)
                    },
                    canDelete = repository.isCustomHomeRow(row.id)
                )
            }
            pluginId to rows
        }
    }

    fun updateJellyfinHomeRow(key: String, enabled: Boolean) {
        val preferenceKey = when (key) {
            "continue" -> HOME_CONTINUE_WATCHING
            "nextUp" -> HOME_NEXT_UP
            "suggestions" -> HOME_SUGGESTIONS
            "latest" -> HOME_LATEST
            else -> null
        } ?: return
        sharedPreferences.edit().putBoolean(preferenceKey, enabled).apply()
        refresh()
    }

    fun updatePluginHomeRow(pluginId: String, rowId: String, enabled: Boolean) {
        if (pluginId.isBlank()) return
        repository.updatePluginHomeRowEnabled(pluginId, rowId, enabled)
        refresh()
    }

    fun addCustomPluginHomeRow(
        pluginId: String,
        templateId: String,
        name: String,
        options: Map<String, String>,
        context: Context
    ) {
        val added = repository.addCustomPluginHomeRow(pluginId, templateId, name, options)
        Toast.makeText(
            context,
            if (added) "Row added" else "Could not add row",
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    fun deleteCustomPluginHomeRow(pluginId: String, rowId: String) {
        repository.deleteCustomPluginHomeRow(pluginId, rowId)
        refresh()
    }

    fun installPlugin(url: String, context: Context, onSuccess: () -> Unit = {}) {
        val trimmedUrl = url.replace(" ", "").trim()
        if (trimmedUrl.isBlank()) {
            Toast.makeText(context, "Enter a plugin manifest URL", Toast.LENGTH_LONG).show()
            return
        }
        
        viewModelScope.launch {
            Timber.e("LOUD PLUGIN LOG: Installing from $trimmedUrl")
            _isInstalling.value = true
            try {
                val result = repository.installPlugin(trimmedUrl)
                if (result.isSuccess) {
                    Toast.makeText(context, "Successfully installed: ${result.getOrNull()?.name}", Toast.LENGTH_LONG).show()
                    onSuccess()
                    refresh()
                } else {
                    Toast.makeText(context, "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                _isInstalling.value = false
            }
        }
    }

    fun uninstallPlugin(pluginId: String?) {
        if (pluginId == null) return
        viewModelScope.launch {
            repository.uninstallPlugin(pluginId)
            refresh()
        }
    }

    companion object {
        private const val HOME_SUGGESTIONS = "home_suggestions"
        private const val HOME_CONTINUE_WATCHING = "home_continue_watching"
        private const val HOME_NEXT_UP = "home_next_up"
        private const val HOME_LATEST = "home_latest"
    }
}

@Composable
fun PluginSettingsScreen(
    onPluginClick: (String) -> Unit = {},
    onJellyfinClick: () -> Unit = {},
    onLocalClick: () -> Unit = {},
    onNetworkClick: () -> Unit = {},
    viewModel: PluginSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val plugins by viewModel.plugins.collectAsState()
    val jellyfinRows by viewModel.jellyfinRows.collectAsState()
    val pluginRows by viewModel.pluginRows.collectAsState()
    val isInstalling by viewModel.isInstalling.collectAsState()
    var installUrl by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var addRowPlugin by remember { mutableStateOf<PluginConfig?>(null) }
    var addRowTemplate by remember { mutableStateOf<PluginHomeRowTemplate?>(null) }
    var addRowName by remember { mutableStateOf("") }
    var addRowFieldValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    if (showScanner) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showScanner = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box {
                    UniversalQrScanner(
                        onUrlFound = { url ->
                            installUrl = url
                            showScanner = false
                        }
                    )
                    
                    Button(
                        onClick = { showScanner = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    if (addRowPlugin != null && addRowTemplate != null) {
        val plugin = addRowPlugin
        val template = addRowTemplate
        AlertDialog(
            onDismissRequest = {
                addRowPlugin = null
                addRowTemplate = null
                addRowName = ""
                addRowFieldValues = emptyMap()
            },
            title = { Text("Add ${template?.name ?: "row"}") },
            text = {
                Column {
                    TextField(
                        value = addRowName,
                        onValueChange = { addRowName = it },
                        label = { Text("Row name") },
                        placeholder = { Text(template?.namePrefix ?: "") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    template?.fields.orEmpty().forEach { field ->
                        TextField(
                            value = addRowFieldValues[field.id] ?: field.default.orEmpty(),
                            onValueChange = { value ->
                                addRowFieldValues = addRowFieldValues + (field.id to value)
                            },
                            label = { Text(field.name) },
                            supportingText = field.description?.let { description -> { Text(description) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedPlugin = plugin ?: return@TextButton
                        val selectedTemplate = template ?: return@TextButton
                        val values = selectedTemplate.fields.associate { field ->
                            field.id to (addRowFieldValues[field.id] ?: field.default.orEmpty()).trim()
                        }
                        val missingRequired = selectedTemplate.fields.any { field ->
                            field.required && values[field.id].isNullOrBlank()
                        }
                        if (missingRequired) {
                            Toast.makeText(context, "Fill in the required fields", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        viewModel.addCustomPluginHomeRow(
                            pluginId = selectedPlugin.id ?: return@TextButton,
                            templateId = selectedTemplate.id,
                            name = addRowName,
                            options = values,
                            context = context
                        )
                        addRowPlugin = null
                        addRowTemplate = null
                        addRowName = ""
                        addRowFieldValues = emptyMap()
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        addRowPlugin = null
                        addRowTemplate = null
                        addRowName = ""
                        addRowFieldValues = emptyMap()
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text("Sources", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Text("Built-in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onJellyfinClick),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Dns, contentDescription = "Jellyfin", modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Jellyfin", style = MaterialTheme.typography.titleMedium)
                            Text("Configured server", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    jellyfinRows.forEach { row ->
                        SourceRowToggle(
                            title = row.name,
                            description = row.description,
                            checked = row.enabled,
                            onCheckedChange = { viewModel.updateJellyfinHomeRow(row.key, it) }
                        )
                    }
                }
            }
            
            Card(onClick = onLocalClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, contentDescription = "Local Storage", modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Local Storage", style = MaterialTheme.typography.titleMedium)
                        Text("Device storage", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(onClick = onNetworkClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lan, contentDescription = "Network", modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Network (SMB/NFS)", style = MaterialTheme.typography.titleMedium)
                        Text("Network attached storage", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Plugins", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = installUrl,
                        onValueChange = { installUrl = it },
                        label = { Text("Plugin Manifest URL") },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "UrlField" }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { showScanner = true }) {
                            Icon(
                                imageVector = Icons.Rounded.QrCode,
                                contentDescription = "Scan QR"
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Button(
                            onClick = { 
                                viewModel.installPlugin(installUrl, context) {
                                    installUrl = ""
                                }
                            },
                            modifier = Modifier.height(56.dp).semantics { contentDescription = "InstallButton" }
                        ) {
                            if (isInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Install Plugin")
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        items(plugins) { plugin ->
            val rows = plugin.id?.let { pluginRows[it] }.orEmpty()
            Card(
                onClick = { plugin.id?.let(onPluginClick) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(plugin.name ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                            Text("Author: ${plugin.author ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                            Text(plugin.description ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                        
                        IconButton(onClick = { viewModel.uninstallPlugin(plugin.id) }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    if (plugin.homeRowTemplates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        plugin.homeRowTemplates.forEach { template ->
                            OutlinedButton(
                                onClick = {
                                    addRowPlugin = plugin
                                    addRowTemplate = template
                                    addRowName = ""
                                    addRowFieldValues = template.fields.associate { it.id to it.default.orEmpty() }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add ${template.name}")
                            }
                        }
                    }

                    if (rows.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        rows.forEach { rowUi ->
                            SourceRowToggle(
                                title = rowUi.row.name,
                                description = rowUi.row.description ?: "Show this row on Home.",
                                checked = rowUi.enabled,
                                canDelete = rowUi.canDelete,
                                onCheckedChange = {
                                    viewModel.updatePluginHomeRow(rowUi.pluginId, rowUi.row.id, it)
                                },
                                onDelete = {
                                    viewModel.deleteCustomPluginHomeRow(rowUi.pluginId, rowUi.row.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRowToggle(
    title: String,
    description: String,
    checked: Boolean,
    canDelete: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                contentDescription = title
            }
        )
        if (canDelete && onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete row",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
