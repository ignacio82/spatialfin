package dev.jdtech.jellyfin.plugins.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.QrCode
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
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PluginSettingsViewModel @Inject constructor(
    private val repository: PluginRepository
) : ViewModel() {
    private val _plugins = MutableStateFlow<List<PluginConfig>>(emptyList())
    val plugins = _plugins.asStateFlow()

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling = _isInstalling.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _plugins.value = repository.getInstalledPlugins()
    }

    fun installPlugin(url: String, context: Context) {
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
                    Timber.e("LOUD PLUGIN LOG: Successfully installed ${result.getOrNull()?.name}")
                    Toast.makeText(context, "Successfully installed: ${result.getOrNull()?.name}", Toast.LENGTH_LONG).show()
                    refresh()
                } else {
                    Timber.e(result.exceptionOrNull(), "LOUD PLUGIN LOG: Installation failed FATALLY")
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
}

@Composable
fun PluginSettingsScreen(
    onPluginClick: (String) -> Unit = {},
    viewModel: PluginSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val plugins by viewModel.plugins.collectAsState()
    val isInstalling by viewModel.isInstalling.collectAsState()
    var installUrl by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Universal Plugins", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
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
                            android.util.Log.e("CRITICAL_PLUGIN", "INSTALL BUTTON CLICKED IMMEDIATELY")
                            Toast.makeText(context, "!!! CLICK DETECTED !!!", Toast.LENGTH_LONG).show()
                            viewModel.installPlugin(installUrl, context) 
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
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(plugins) { plugin ->
                Card(
                    onClick = { plugin.id?.let(onPluginClick) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
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
                }
            }
        }
    }
}
