package dev.jdtech.jellyfin.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession

@Composable
fun MusicAssistantAuthDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by SendspinReceiverSession.state.collectAsStateWithLifecycle()
    
    var serverUrl by remember(state.musicAssistantServerUrl) { mutableStateOf(state.musicAssistantServerUrl.orEmpty()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    
    val loading = state.musicAssistantLoading
    
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Music Assistant Setup", style = MaterialTheme.typography.titleLarge)
            
            state.musicAssistantError?.takeIf(String::isNotBlank)?.let { error ->
                Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            
            Text(
                text = when (state.musicAssistantAuthState) {
                    SendspinMusicAssistantAuthState.INVALID -> "Music Assistant rejected the saved token."
                    SendspinMusicAssistantAuthState.AUTHENTICATING -> "Signing in..."
                    SendspinMusicAssistantAuthState.AUTHENTICATED -> "Connected successfully."
                    else -> "Connect SpatialFin to Music Assistant to manage speakers and view recommendations."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                singleLine = true,
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.89:8095") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { SendspinReceiverService.setMusicAssistantServerUrl(context.applicationContext, serverUrl) },
                    enabled = !loading && serverUrl.isNotBlank(),
                ) {
                    Text("Use server")
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { SendspinReceiverService.loginMusicAssistant(context.applicationContext, serverUrl, username, password) },
                enabled = !loading && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign in")
            }
            HorizontalDivider()
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                label = { Text("Access token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { SendspinReceiverService.saveMusicAssistantToken(context.applicationContext, serverUrl, token) },
                enabled = !loading && serverUrl.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save token")
            }
            
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}
