package dev.jdtech.jellyfin.presentation.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.spatialfin.presentation.theme.spacings

@Composable
fun AddShareScreen(
    navigateBack: () -> Unit,
    viewModel: AddShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val safePadding = rememberSafePadding()

    var protocol by remember { mutableStateOf("smb") }
    var host by remember { mutableStateOf("") }
    var shareName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.discoverShares() }

    LaunchedEffect(state.saved) {
        if (state.saved) navigateBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = safePadding.start + MaterialTheme.spacings.default,
                    top = safePadding.top + MaterialTheme.spacings.default,
                    end = safePadding.end + MaterialTheme.spacings.default,
                ),
        ) {
            XrBrowseHeader(
                title = stringResource(CoreR.string.network_add_share),
                onBackClick = navigateBack,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = safePadding.start + 24.dp,
                    top = safePadding.top + 112.dp,
                    end = safePadding.end + 24.dp,
                    bottom = safePadding.bottom + 24.dp,
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            // Protocol selector
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = protocol == "smb",
                    onClick = { protocol = "smb" },
                    label = { Text("SMB") },
                )
                FilterChip(
                    selected = protocol == "nfs",
                    onClick = { protocol = "nfs" },
                    label = { Text("NFS") },
                )
            }

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(CoreR.string.network_host)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = shareName,
                onValueChange = { shareName = it },
                label = {
                    Text(
                        if (protocol == "nfs") {
                            stringResource(CoreR.string.network_export_path)
                        } else {
                            stringResource(CoreR.string.network_share_name)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            // Credentials only for SMB
            if (protocol == "smb") {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(CoreR.string.network_username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(CoreR.string.network_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(CoreR.string.network_domain)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(CoreR.string.network_display_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        viewModel.testConnection(protocol, host, shareName, username, password, domain)
                    },
                    enabled = host.isNotBlank() && shareName.isNotBlank() && !state.isTesting,
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(stringResource(CoreR.string.network_test_connection))
                }
                Button(
                    onClick = {
                        viewModel.saveShare(protocol, host, shareName, username, password, domain, displayName)
                    },
                    enabled = host.isNotBlank() && shareName.isNotBlank() && !state.isSaving,
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(stringResource(CoreR.string.network_save))
                }
            }

            state.testResult?.let { success ->
                Text(
                    text = if (success) {
                        stringResource(CoreR.string.network_test_success)
                    } else {
                        stringResource(CoreR.string.network_test_failed)
                    },
                    color = if (success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Discovered shares
            Text(
                text = stringResource(CoreR.string.network_discovered_shares),
                style = MaterialTheme.typography.titleLarge,
            )

            if (state.isDiscovering) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(CoreR.string.network_discovering))
                }
            } else if (state.discoveredShares.isEmpty()) {
                Text(
                    text = stringResource(CoreR.string.network_no_discovered),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.discoveredShares.forEach { share ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                protocol = share.protocol
                                host = share.host
                                shareName = share.serviceName
                            },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = share.serviceName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "${share.protocol.uppercase()}://${share.host}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
