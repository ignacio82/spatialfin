package dev.jdtech.jellyfin.presentation.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.util.hasLocalVideoAccess
import dev.jdtech.jellyfin.core.util.localVideoPermissions
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.presentation.components.FinishSetupCard
import dev.jdtech.jellyfin.presentation.film.components.BrowseHeaderAction
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ErrorCard
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.GridCellsAdaptiveWithMinColumns
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.spatialfin.presentation.theme.spacings
import dev.spatialfin.unified.applock.LocalSystemModalGraceHook

@Composable
fun LocalMediaScreen(
    hasServers: Boolean,
    appPreferences: AppPreferences,
    onItemClick: (LocalVideoItem) -> Unit,
    onManageServersClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLanguageSettingsClick: () -> Unit,
    viewModel: LocalMediaViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val graceHook = LocalSystemModalGraceHook.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(hasLocalVideoAccess(context)) }
    val needsLanguageSetup =
        appPreferences.getValue(appPreferences.smartSpokenLanguages).isNullOrBlank()
    var languagesDismissed by remember {
        mutableStateOf(appPreferences.getValue(appPreferences.finishSetupLanguagesDismissed))
    }
    var permissionModalActive by remember { mutableStateOf(false) }

    DisposableEffect(graceHook) {
        onDispose {
            if (permissionModalActive) {
                permissionModalActive = false
                graceHook.setSystemModalActive(false)
            }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (permissionModalActive) {
                permissionModalActive = false
                graceHook.setSystemModalActive(false)
            }
            hasPermission = hasLocalVideoAccess(context)
            if (hasPermission) {
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
        needsLanguageSetup = needsLanguageSetup && !languagesDismissed,
        state = state,
        onGrantPermission = {
            if (!permissionModalActive) {
                permissionModalActive = true
                graceHook.setSystemModalActive(true)
            }
            permissionLauncher.launch(localVideoPermissions())
        },
        onItemClick = onItemClick,
        onManageServersClick = onManageServersClick,
        onSettingsClick = onSettingsClick,
        onLanguageSettingsClick = onLanguageSettingsClick,
        onDismissLanguagePrompt = {
            appPreferences.setValue(appPreferences.finishSetupLanguagesDismissed, true)
            languagesDismissed = true
        },
        onRetry = { viewModel.loadVideos() },
    )
}

@Composable
private fun LocalMediaScreenLayout(
    hasPermission: Boolean,
    hasServers: Boolean,
    needsLanguageSetup: Boolean,
    state: LocalMediaState,
    onGrantPermission: () -> Unit,
    onItemClick: (LocalVideoItem) -> Unit,
    onManageServersClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLanguageSettingsClick: () -> Unit,
    onDismissLanguagePrompt: () -> Unit,
    onRetry: () -> Unit,
) {
    val contentPadding = PaddingValues(24.dp)
    val finishSetupItems =
        buildList {
            if (!hasServers) {
                add(
                    FinishSetupItem(
                        titleRes = SetupR.string.connect_to_jellyfin_server,
                        bodyRes = SetupR.string.connect_to_jellyfin_server_body,
                        actionRes = SetupR.string.servers_btn_add_server,
                        onClick = onManageServersClick,
                        onDismiss = null,
                    ),
                )
            }
            if (needsLanguageSetup) {
                add(
                    FinishSetupItem(
                        titleRes = CoreR.string.finish_setup_languages_title,
                        bodyRes = CoreR.string.finish_setup_languages_body,
                        actionRes = CoreR.string.finish_setup_languages_action,
                        onClick = onLanguageSettingsClick,
                        onDismiss = onDismissLanguagePrompt,
                    ),
                )
            }
        }
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !hasPermission -> {
                EmptyState(
                    title = stringResource(CoreR.string.local_permission_title),
                    body = stringResource(CoreR.string.local_permission_body),
                    action = stringResource(CoreR.string.local_permission_action),
                    onAction = onGrantPermission,
                    modifier = Modifier.padding(top = 108.dp),
                )
            }
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 108.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                ErrorCard(
                    onShowStacktrace = {},
                    onRetryClick = onRetry,
                    modifier = Modifier.fillMaxWidth().padding(24.dp).padding(top = 108.dp),
                )
            }
            state.items.isEmpty() -> {
                EmptyState(
                    title = stringResource(CoreR.string.local_empty_title),
                    body = stringResource(CoreR.string.local_empty_body),
                    action = if (hasServers) null else stringResource(SetupR.string.connect_to_jellyfin_server),
                    onAction = onManageServersClick,
                    modifier = Modifier.padding(top = 108.dp),
                )
            }
            else -> {
                val visibleItems = remember(state.items) {
                    state.items.deduplicateMovieVersions().filterIsInstance<LocalVideoItem>()
                }
                Column(modifier = Modifier.fillMaxSize().padding(top = 108.dp)) {
                    if (finishSetupItems.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                        ) {
                            finishSetupItems.forEach { item ->
                                FinishSetupCard(
                                    title = stringResource(item.titleRes),
                                    body = stringResource(item.bodyRes),
                                    actionLabel = stringResource(item.actionRes),
                                    onActionClick = item.onClick,
                                    onDismiss = item.onDismiss,
                                )
                            }
                            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCellsAdaptiveWithMinColumns(minSize = 220.dp, minColumns = 2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                    ) {
                        items(visibleItems, key = { it.mediaStoreId }) { item ->
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
        XrBrowseHeader(
            title = stringResource(CoreR.string.title_local),
            primaryAction =
                BrowseHeaderAction(
                    label = "Settings",
                    icon = CoreR.drawable.ic_settings,
                    onClick = onSettingsClick,
                ),
            secondaryAction =
                if (!hasServers) {
                    BrowseHeaderAction(
                        label = "Servers",
                        icon = CoreR.drawable.ic_server,
                        onClick = onManageServersClick,
                    )
                } else {
                    null
                },
            modifier = Modifier.padding(contentPadding),
        )
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

private data class FinishSetupItem(
    val titleRes: Int,
    val bodyRes: Int,
    val actionRes: Int,
    val onClick: () -> Unit,
    val onDismiss: (() -> Unit)? = null,
)
