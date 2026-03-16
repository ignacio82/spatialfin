package dev.jdtech.jellyfin.presentation.setup.servers

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.presentation.components.XrConfirmDialog
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.jdtech.jellyfin.presentation.setup.components.ServerBottomSheet
import dev.jdtech.jellyfin.presentation.setup.components.ServerItem
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.servers.ServersAction
import dev.jdtech.jellyfin.setup.presentation.servers.ServersEvent
import dev.jdtech.jellyfin.setup.presentation.servers.ServersState
import dev.jdtech.jellyfin.setup.presentation.servers.ServersViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(
    navigateToUsers: () -> Unit,
    navigateToAddresses: (serverId: String) -> Unit,
    onAddClick: () -> Unit,
    onBackClick: () -> Unit,
    showBack: Boolean = true,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadServers() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ServersEvent.ServerChanged -> navigateToUsers()
            else -> Unit
        }
    }

    ServersScreenLayout(
        state = state,
        showBack = showBack,
        onAction = { action ->
            when (action) {
                is ServersAction.OnAddClick -> onAddClick()
                is ServersAction.OnBackClick -> onBackClick()
                is ServersAction.NavigateToAddresses -> navigateToAddresses(action.serverId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersScreenLayout(
    state: ServersState,
    showBack: Boolean = true,
    onAction: (ServersAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var openDeleteDialog by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf<ServerWithAddresses?>(null) }

    RootLayout {
        Column(
            modifier =
                Modifier.padding(horizontal = 24.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .align(Alignment.Center)
        ) {
            Spacer(modifier = Modifier.weight(0.2f))
            Image(
                painter = painterResource(id = CoreR.drawable.ic_banner),
                contentDescription = null,
                modifier = Modifier.width(320.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = stringResource(SetupR.string.servers),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(modifier = Modifier.height(32.dp))
            if (state.servers.isEmpty()) {
                Text(
                    text = stringResource(SetupR.string.servers_no_servers),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(state.servers) { server ->
                        ServerItem(
                            name = server.server.name,
                            address = server.addresses.first().address,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onAction(ServersAction.OnServerClick(serverId = server.server.id))
                            },
                            onLongClick = {
                                selectedServer = server
                                showBottomSheet = true
                            },
                        )
                    }
                }
            }
        }
        if (showBack) {
            FilledTonalButton(
                onClick = { onAction(ServersAction.OnBackClick) },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_left),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Back", style = MaterialTheme.typography.titleMedium)
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onAction(ServersAction.OnAddClick) },
            icon = { Icon(painterResource(CoreR.drawable.ic_plus), contentDescription = null) },
            text = {
                Text(
                    text = stringResource(SetupR.string.servers_btn_add_server),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )
    }

    if (openDeleteDialog && selectedServer != null) {
        XrConfirmDialog(
            title = stringResource(SetupR.string.remove_server_dialog),
            message =
                stringResource(
                    SetupR.string.remove_server_dialog_text,
                    selectedServer!!.server.name,
                ),
            confirmLabel = stringResource(SetupR.string.confirm),
            dismissLabel = stringResource(SetupR.string.cancel),
            onDismiss = { openDeleteDialog = false },
            onConfirm = {
                openDeleteDialog = false
                scope
                    .launch { sheetState.hide() }
                    .invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            showBottomSheet = false
                        }
                    }
                onAction(ServersAction.DeleteServer(selectedServer!!.server.id))
            },
        )
    }

    if (showBottomSheet && selectedServer != null) {
        ServerBottomSheet(
            name = selectedServer!!.server.name,
            address = selectedServer!!.addresses.first().address,
            onAddresses = {
                showBottomSheet = false
                onAction(ServersAction.NavigateToAddresses(selectedServer!!.server.id))
            },
            onRemoveServer = { openDeleteDialog = true },
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
        )
    }
}

@PreviewScreenSizes
@Composable
private fun ServersScreenLayoutPreview() {
    SpatialFinTheme {
        ServersScreenLayout(
            state =
                ServersState(
                    servers =
                        listOf(
                            ServerWithAddresses(
                                server = dummyServer,
                                addresses = listOf(dummyServerAddress),
                                user = null,
                            )
                        )
                ),
            onAction = {},
        )
    }
}
