package dev.jdtech.jellyfin.presentation.local

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ErrorCard
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.spatialfin.presentation.theme.spacings

@Composable
fun LocalMediaScreen(
    hasServers: Boolean,
    onItemClick: (LocalVideoItem) -> Unit,
    onManageServersClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: LocalMediaViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, localVideoPermission()) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
            if (granted) {
                viewModel.loadVideos()
            }
        }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadVideos()
        }
    }

    LocalMediaScreenLayout(
        hasPermission = hasPermission,
        hasServers = hasServers,
        state = state,
        onGrantPermission = { permissionLauncher.launch(localVideoPermission()) },
        onItemClick = onItemClick,
        onManageServersClick = onManageServersClick,
        onSettingsClick = onSettingsClick,
        onRetry = { viewModel.loadVideos() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalMediaScreenLayout(
    hasPermission: Boolean,
    hasServers: Boolean,
    state: LocalMediaState,
    onGrantPermission: () -> Unit,
    onItemClick: (LocalVideoItem) -> Unit,
    onManageServersClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.title_local)) },
                colors = TopAppBarDefaults.topAppBarColors(),
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_settings),
                            contentDescription = null,
                        )
                    }
                    if (!hasServers) {
                        IconButton(onClick = onManageServersClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_server),
                                contentDescription = null,
                            )
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        when {
            !hasPermission -> {
                EmptyState(
                    title = stringResource(CoreR.string.local_permission_title),
                    body = stringResource(CoreR.string.local_permission_body),
                    action = stringResource(CoreR.string.local_permission_action),
                    onAction = onGrantPermission,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                ErrorCard(
                    onShowStacktrace = {},
                    onRetryClick = onRetry,
                    modifier = Modifier.fillMaxWidth().padding(24.dp).padding(innerPadding),
                )
            }
            state.items.isEmpty() -> {
                EmptyState(
                    title = stringResource(CoreR.string.local_empty_title),
                    body = stringResource(CoreR.string.local_empty_body),
                    action = if (hasServers) null else stringResource(CoreR.string.manage_servers),
                    onAction = onManageServersClick,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(24.dp) + innerPadding,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                ) {
                    items(state.items, key = { it.mediaStoreId }) { item ->
                        ItemCard(
                            item = item,
                            direction = Direction.VERTICAL,
                            onClick = { onItemClick(item) },
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = Modifier.fillMaxSize().then(modifier), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
            action?.let {
                Button(onClick = onAction) { Text(it) }
            }
        }
    }
}
