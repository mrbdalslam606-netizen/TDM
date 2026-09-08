package com.tdm.app.ui.screens.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tdm.app.di.AppContainer
import com.tdm.app.telegram.TgAuthState
import kotlinx.coroutines.launch

/**
 * First-run login (spec §3): phone → OTP → 2FA → ready.
 * Session persists in TDLib's app-private database; login is never asked again (spec §3, §68).
 */
@Composable
fun LoginScreen(container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = container.settingsRepository.settings.collectAsState(initial = null).value

    var apiId by remember { mutableStateOf(settings?.apiId?.takeIf { it != 0 }?.toString() ?: "") }
    var apiHash by remember { mutableStateOf(settings?.apiHash ?: "") }
    var phone by remember { mutableStateOf(settings?.phoneNumberHint ?: "") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val auth = container.telegram.authState.collectAsState().value
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(auth) {
        // Initialization hands control to the next auth screen; unlock its actions.
        busy = false
        if (auth == TgAuthState.Ready) {
            container.settingsRepository.update { it.copy(loggedIn = true) }
            com.tdm.app.service.DownloadForegroundService.start(ctx, "login")
            onDone()
        }
    }

    fun err(t: Throwable) { busy = false; error = t.message ?: "error" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TDM", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Telegram Download Manager",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        when (auth) {
            TgAuthState.Idle, TgAuthState.Initializing, null -> {
                // API credentials step
                if (settings?.apiId == 0 || settings?.apiHash.isNullOrBlank()) {
                    OutlinedTextField(
                        value = apiId, onValueChange = { apiId = it },
                        label = { Text("api_id") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiHash, onValueChange = { apiHash = it },
                        label = { Text("api_hash") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "Get both from my.telegram.org → API development tools (one time). Stored privately on this device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true; error = ""
                                runCatching {
                                    container.settingsRepository.update {
                                        it.copy(apiId = apiId.toIntOrNull() ?: 0, apiHash = apiHash.trim())
                                    }
                                    container.telegram.init(apiId.toIntOrNull() ?: 0, apiHash.trim())
                                }.onFailure(::err)
                            }
                        },
                        enabled = !busy && LoginValidation.isValidApiId(apiId) &&
                            LoginValidation.isValidApiHash(apiHash),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Connecting…" else "Continue") }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching {
                                    container.telegram.init(settings!!.apiId, settings.apiHash)
                                }.onFailure(::err)
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Restoring session…" else "Continue") }
                }
            }
            TgAuthState.WaitingPhone -> {
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone number (+…)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            container.settingsRepository.update { it.copy(phoneNumberHint = phone.trim()) }
                            container.telegram.sendPhoneNumber(phone)
                        }.onSuccess { busy = false }.onFailure(::err)
                    }
                }, enabled = !busy && LoginValidation.isValidPhone(phone), modifier = Modifier.fillMaxWidth()) {
                    Text("Send code")
                }
            }
            TgAuthState.WaitingCode -> {
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Login code") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        busy = true
                        runCatching { container.telegram.submitCode(code) }
                            .onSuccess { busy = false }.onFailure(::err)
                    }
                }, enabled = !busy && LoginValidation.isValidCode(code), modifier = Modifier.fillMaxWidth()) {
                    Text("Verify")
                }
                TextButton(onClick = { scope.launch { runCatching { container.telegram.resendCode() } } }) {
                    Text("Resend code")
                }
            }
            TgAuthState.WaitingPassword -> {
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("2FA password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        busy = true
                        runCatching { container.telegram.submitPassword(password) }
                            .onSuccess { busy = false }.onFailure(::err)
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Verify password")
                }
            }
            TgAuthState.LoggingIn -> CircularProgressIndicator()
            is TgAuthState.Failed -> {
                Text("Error: ${auth.message}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    scope.launch {
                        runCatching { container.telegram.init(settings?.apiId ?: 0, settings?.apiHash ?: "") }
                    }
                }) { Text("Retry") }
            }
            else -> CircularProgressIndicator()
        }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
