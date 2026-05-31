package dev.jdtech.jellyfin.plugins.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jdtech.jellyfin.plugins.model.UniversalMediaItem
import dev.jdtech.jellyfin.plugins.repository.PluginContentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UniversalContentViewModel @Inject constructor(
    private val repository: PluginContentRepository
) : ViewModel() {
    private val _items = MutableStateFlow<List<UniversalMediaItem>>(emptyList())
    val items = _stateFlow { _items } // Simplified for this draft

    init {
        load()
    }

    private fun _stateFlow(block: () -> MutableStateFlow<List<UniversalMediaItem>>) = block().asStateFlow()

    fun load() {
        viewModelScope.launch {
            _items.value = repository.getHome()
        }
    }
}

@Composable
fun UniversalHomeScreen(
    viewModel: UniversalContentViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Universal Content", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn {
            items(items) { item ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(item.author ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
