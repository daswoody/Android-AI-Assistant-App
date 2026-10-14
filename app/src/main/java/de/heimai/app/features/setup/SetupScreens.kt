package de.heimai.app.features.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.heimai.app.HeimAiApp
import de.heimai.app.core.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * Schritt 1 des App-Starts: Server-Auswahl. Die URL des Voice-Orchestrators
 * (z. B. http://192.168.2.105:8200 oder https://orchestrator.ai.lab) wird
 * per /v1/health validiert und gespeichert.
 */
@Composable
fun ServerScreen(onConnected: () -> Unit) {
    val container = HeimAiApp.from(LocalContext.current.applicationContext as android.app.Application).container
    val scope = rememberCoroutineScope()
    val saved by container.settings.settings.collectAsState(initial = AppSettings())

    var url by remember(saved.serverUrl) { mutableStateOf(saved.serverUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp).imePadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Heim-AI", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Mit welchem Server möchtest du dich verbinden?", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text("Server-URL") },
            placeholder = { Text("http://192.168.2.105:8200") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = url.isNotBlank() && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    val normalized = if (url.startsWith("http")) url.trim() else "http://${url.trim()}"
                    try {
                        container.api.health(normalized)
                        container.settings.setServerUrl(normalized)
                        onConnected()
                    } catch (e: Exception) {
                        error = "Server nicht erreichbar: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Verbinden")
        }
    }
}

/** Schritt 2: Login gegen den gewählten Server (POST /v1/auth/login). */
@Composable
fun LoginScreen(onChangeServer: () -> Unit, onLoggedIn: () -> Unit) {
    val container = HeimAiApp.from(LocalContext.current.applicationContext as android.app.Application).container
    val scope = rememberCoroutineScope()
    val saved by container.settings.settings.collectAsState(initial = AppSettings())

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp).imePadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Anmelden", style = MaterialTheme.typography.headlineMedium)
        Text(saved.serverUrl, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text("Benutzername") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Passwort") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = username.isNotBlank() && password.isNotBlank() && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val response = container.api.login(
                            saved.serverUrl, username, password, android.os.Build.MODEL
                        )
                        container.settings.setAuth(response.token, response.user.name, response.user.tier)
                        container.cardLayouts.sync()
                        onLoggedIn()
                    } catch (e: Exception) {
                        error = "Login fehlgeschlagen: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Anmelden")
        }
        TextButton(onClick = onChangeServer) { Text("Anderen Server wählen") }
    }
}
