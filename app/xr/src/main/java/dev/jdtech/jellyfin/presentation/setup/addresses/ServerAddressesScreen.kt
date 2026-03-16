package dev.jdtech.jellyfin.presentation.setup.addresses

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServerAddress
import dev.jdtech.jellyfin.models.ServerAddress
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesAction
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesState
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesViewModel

@Composable
fun ServerAddressesScreen(
    serverId: String,
    navigateBack: () -> Unit,
    viewModel: ServerAddressesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadAddresses(serverId) }

    ServerAddressesLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is ServerAddressesAction.OnBackClick -> navigateBack()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
fun ServerAddressesLayout(state: ServerAddressesState, onAction: (ServerAddressesAction) -> Unit) {
    val safePadding = rememberSafePadding()
    var selectedAddress by remember { mutableStateOf<ServerAddress?>(null) }
    var openAddDialog by remember { mutableStateOf(false) }
    var openDeleteDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = safePadding.start + MaterialTheme.spacings.default,
                        top = safePadding.top + MaterialTheme.spacings.default,
                        end = safePadding.end + MaterialTheme.spacings.default,
                    ),
        ) {
            XrBrowseHeader(
                title = stringResource(SetupR.string.addresses),
                onBackClick = { onAction(ServerAddressesAction.OnBackClick) },
                primaryAction =
                    dev.jdtech.jellyfin.presentation.film.components.BrowseHeaderAction(
                        label = "Add",
                        icon = CoreR.drawable.ic_plus,
                        onClick = { openAddDialog = true },
                    ),
            )
        }
        Column(modifier = Modifier.padding(top = safePadding.top + 96.dp)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = safePadding.start + MaterialTheme.spacings.default,
                        top = MaterialTheme.spacings.default,
                        end = safePadding.end + MaterialTheme.spacings.default,
                        bottom = safePadding.bottom + 96.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
            ) {
                items(items = state.addresses, key = { it.id }) { address ->
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clip(CardDefaults.outlinedShape)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        selectedAddress = address
                                        openDeleteDialog = true
                                    },
                                )
                                .padding(MaterialTheme.spacings.small),
                    ) {
                        Text(
                            address.address,
                            modifier = Modifier.padding(MaterialTheme.spacings.large),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            text = {
                Text(
                    stringResource(SetupR.string.add_address),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            icon = {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_plus),
                    contentDescription = null,
                )
            },
            onClick = { openAddDialog = true },
            modifier = Modifier.padding(24.dp).align(androidx.compose.ui.Alignment.BottomEnd),
        )
    }

    if (openAddDialog) {
        AddServerAddressDialog(
            onAdd = { address ->
                onAction(ServerAddressesAction.AddAddress(address))
                openAddDialog = false
            },
            onDismiss = { openAddDialog = false },
        )
    }

    if (openDeleteDialog && selectedAddress != null) {
        DeleteServerAddressDialog(
            address = selectedAddress!!.address,
            onConfirm = {
                onAction(ServerAddressesAction.DeleteAddress(selectedAddress!!.id))
                openDeleteDialog = false
            },
            onDismiss = { openDeleteDialog = false },
        )
    }
}

@PreviewScreenSizes
@Composable
private fun ServerAddressesLayoutPreview() {
    SpatialFinTheme {
        ServerAddressesLayout(
            state = ServerAddressesState(addresses = listOf(dummyServerAddress)),
            onAction = {},
        )
    }
}
