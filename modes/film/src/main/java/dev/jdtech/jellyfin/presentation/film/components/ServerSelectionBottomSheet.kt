package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import dev.jdtech.jellyfin.setup.presentation.servers.ServersAction
import dev.jdtech.jellyfin.setup.presentation.servers.ServersEvent
import dev.jdtech.jellyfin.setup.presentation.servers.ServersState
import dev.jdtech.jellyfin.setup.presentation.servers.ServersViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSelectionBottomSheet(
    currentServerId: String,
    onUpdate: () -> Unit,
    onManage: () -> Unit,
    onNavigateToUsers: (serverId: String, serverName: String, serverAddress: String) -> Unit = { _, _, _ -> },
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var failureCode by remember { mutableStateOf<OnboardingFailureCode?>(null) }

    LaunchedEffect(Unit) { viewModel.loadServers() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ServersEvent.ServerChanged -> {
                failureCode = null
                onUpdate()
            }
            is ServersEvent.NavigateToUsers -> {
                failureCode = null
                onDismissRequest()
                onNavigateToUsers(event.serverId, event.serverName, event.serverAddress)
            }
            is ServersEvent.Failed -> failureCode = event.code
        }
    }

    ServerSelectionBottomSheetLayout(
        currentServerId = currentServerId,
        state = state,
        failureCode = failureCode,
        onManage = onManage,
        onAction = { action ->
            failureCode = null
            viewModel.onAction(action)
        },
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSelectionBottomSheetLayout(
    currentServerId: String,
    state: ServersState,
    failureCode: OnboardingFailureCode? = null,
    onManage: () -> Unit,
    onAction: (action: ServersAction) -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        LazyColumn(
            contentPadding =
                PaddingValues(
                    start = MaterialTheme.spacings.medium,
                    end = MaterialTheme.spacings.medium,
                    bottom = MaterialTheme.spacings.default,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            if (failureCode != null) {
                item(key = "error") {
                    Text(
                        text = "${OnboardingFailureMessages.message(context, failureCode)} [${failureCode.wireCode}]",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            items(items = state.servers, key = { it.server.id }) { server ->
                ServerSelectionItem(
                    server = server,
                    selected = server.server.id == currentServerId,
                    onClick = { onAction(ServersAction.OnServerClick(server.server.id)) },
                    onClickAddress = { addressId ->
                        onAction(
                            ServersAction.OnAddressClick(
                                serverId = server.server.id,
                                addressId = addressId,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "manage") {
                OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(CoreR.string.manage_servers))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
private fun ServerSelectionBottomSheetPreview() {
    SpatialFinTheme {
        ServerSelectionBottomSheetLayout(
            currentServerId = "",
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
            onManage = {},
            onAction = {},
            onDismissRequest = {},
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Expanded),
        )
    }
}
