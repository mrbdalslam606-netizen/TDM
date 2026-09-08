package com.tdm.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tdm.app.core.reliability.DeviceCompatibility
import com.tdm.app.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Background Reliability Assistant (spec §37): full system state at a glance +
 * "Run Reliability Check" with fix guidance. Shizuku state included (spec §34).
 */
@Composable
fun ReliabilityScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = container.settingsRepository.settings.collectAsState(initial = null).value ?: return
    val conn = container.telegram.connState.collectAsState().value

    var check by remember { mutableStateOf<DeviceCompatibility.Check?>(null) }
    var engineHealth by remember { mutableStateOf("checking…") }
    val snap = container.engineState?.snapshot?.collectAsState()?.value

    LaunchedEffect(Unit) {
        engineHealth = container.recovery.engineHealth().let { h ->
            if (h.healthy) "Running ✓" else if (h.processAlive) "Stale heartbeat" else "Process dead"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Background Reliability", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        StatusRow("Shizuku", check?.let { if (it.shizukuRunning) "Running" else "Not Running" } ?: "unknown")
        StatusRow("Permission", check?.let { if (it.shizukuPermission) "Granted" else "Denied" } ?: "unknown")
        StatusRow("Battery Optimization", check?.let { if (it.batteryOptimized) "Ignored ✓" else "Active" } ?: "unknown")
        StatusRow("Background Restriction", check?.let {
            when (it.backgroundRestricted) { true -> "Restricted"; false -> "Allowed ✓"; null -> "Unknown" }
        } ?: "unknown")
        StatusRow("Foreground Service", if (snap?.runState != com.tdm.app.core.model.EngineRunState.STOPPED) "Running" else "Stopped")
        StatusRow("Watchdog", if (settings.reliabilityWatchdogEnabled) "Active" else "Disabled")
        StatusRow("Download Engine", engineHealth)
        StatusRow("TDLib", when (conn) {
            com.tdm.app.telegram.TgConnState.READY -> "Connected ✓"
            com.tdm.app.telegram.TgConnState.CONNECTING -> "Connecting"
            com.tdm.app.telegram.TgConnState.WAITING_NETWORK -> "Waiting for network"
            null -> "unknown"
            else -> "Disconnected"
        })

        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            scope.launch {
                check = container.deviceCompatibility.runCheck(container.shizuku)
                engineHealth = container.recovery.engineHealth().let { h ->
                    if (h.healthy) "Running ✓" else if (h.processAlive) "Stale heartbeat" else "Process dead"
                }
            }
        }) { Text("Run Reliability Check") }

        check?.let { c ->
            Spacer(Modifier.height(12.dp))
            Text("Device profile: ${c.deviceProfile}", style = MaterialTheme.typography.bodyMedium)
            Text(
                c.guidance, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!c.batteryOptimized) {
                val batteryIntent = container.deviceCompatibility.profile.batteryOptimizationIntent()
                Button(onClick = {
                    runCatching { ctx.startActivity(batteryIntent) }
                }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Request battery optimization exemption")
                }
            }
            if (c.shizukuRunning && !c.shizukuPermission) {
                Button(onClick = { container.shizuku.requestPermission(101) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Grant Shizuku permission")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("← Back") }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value, style = MaterialTheme.typography.bodyMedium,
            color = if (value.contains("✓")) MaterialTheme.colorScheme.primary
            else if (value in setOf("Active", "Denied", "Restricted", "Process dead", "Stopped"))
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
