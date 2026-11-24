package dev.spatialfin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.presentation.network.AddShareEvent
import dev.jdtech.jellyfin.presentation.network.AddShareViewModel
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@Composable
internal fun TvAddShareScreen(
    viewModel: AddShareViewModel,
    showSkipSetup: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var protocol by rememberSaveable { mutableStateOf("smb") }
    var host by rememberSaveable { mutableStateOf("") }
    var shareName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    val primaryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        viewModel.discoverShares()
        runCatching { primaryFocus.requestFocus() }
    }

    ObserveAsEvents(viewModel.events) { event ->
        if (event is AddShareEvent.Saved) {
            onBack()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 48.dp, bottom = 48.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(SetupR.string.add_network_share),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Connect an SMB or NFS share on your local network.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (showSkipSetup) {
                        OutlinedButton(
                            onClick = {
                                viewModel.activateStandalone(SessionChangeReason.STANDALONE_ONBOARDING)
                            },
                        ) {
                            Text(stringResource(SetupR.string.skip_setup))
                        }
                    }
                    OutlinedButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            }
        }

        if (state.discoveredShares.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Discovered network devices", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.discoveredShares.take(6).forEach { discovered ->
                            OutlinedButton(
                                onClick = {
                                    protocol = discovered.protocol
                                    host = discovered.host
                                    if (displayName.isBlank()) {
                                        displayName = discovered.serviceName
                                    }
                                },
                            ) {
                                Text("${discovered.serviceName} (${discovered.host})")
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { protocol = "smb" }) {
                        Text(if (protocol == "smb") "• SMB" else "SMB")
                    }
                    Button(onClick = { protocol = "nfs" }) {
                        Text(if (protocol == "nfs") "• NFS" else "NFS")
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { androidx.compose.material3.Text("Host or IP address", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(primaryFocus),
                    colors = tvTextFieldColors(),
                )
                OutlinedTextField(
                    value = shareName,
                    onValueChange = { shareName = it },
                    label = { androidx.compose.material3.Text("Share name / export path", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tvTextFieldColors(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { androidx.compose.material3.Text("Username (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tvTextFieldColors(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { androidx.compose.material3.Text("Password (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = tvTextFieldColors(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { androidx.compose.material3.Text("Display name (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tvTextFieldColors(),
                )

                state.error?.let { err ->
                    TvInlineErrorBanner(message = err)
                }

                Button(
                    onClick = {
                        viewModel.saveShare(
                            protocol = protocol,
                            host = host.trim(),
                            shareName = shareName.trim(),
                            username = username.trim().takeIf { it.isNotBlank() },
                            password = password.takeIf { it.isNotBlank() },
                            domain = null,
                            displayName = displayName.trim().takeIf { it.isNotBlank() },
                            activateStandaloneIfUnconfigured = true,
                        )
                    },
                    enabled = !state.isSaving && host.isNotBlank() && shareName.isNotBlank(),
                ) {
                    Text(if (state.isSaving) "Saving…" else "Save share")
                }
            }
        }
    }
}
