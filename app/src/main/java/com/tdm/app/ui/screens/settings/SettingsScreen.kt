package com.tdm.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tdm.app.data.db.AccountEntity
import com.tdm.app.data.db.NetworkPolicy
import com.tdm.app.di.AppContainer
import kotlinx.coroutines.launch

/** Settings with account switching/removal and global download controls. */
@Composable
fun SettingsScreen(container: AppContainer, pickTree: () -> Unit, onAccountRemoved: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val s = container.settingsRepository.settings.collectAsState(initial = null).value ?: return
    val accounts by container.database.accountDao().observeAll().collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SettingsHeader("Telegram Accounts")
        Text(
            "Current account${s.phoneNumberHint.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row {
            TextButton(onClick = {
                scope.launch {
                    container.accountManager.logoutCurrentAndRemove()
                    onAccountRemoved()
                }
            }) { Text("Log out and remove", color = MaterialTheme.colorScheme.error) }
        }
        accounts.forEach { account ->
            AccountRow(
                account = account,
                selected = account.id == s.currentAccountId,
                onSelect = { scope.launch { container.accountManager.switchAccount(account.id) } },
                onDelete = { scope.launch { container.accountManager.removeAccount(account.id) } },
            )
        }

        SettingsHeader("Inbox")
        Text(
            if (s.inboxChatId == 0L) "Inbox: Saved Messages" else "Inbox chat id: ${s.inboxChatId}",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = s.inboxChatId.toString(),
            onValueChange = { v -> scope.launch { container.settingsRepository.update { it.copy(inboxChatId = v.toLongOrNull() ?: 0L) } } },
            label = { Text("Inbox chat id (0 = Saved Messages)") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        SettingsHeader("Storage")
        Text(if (s.storageTreeUri.isBlank()) "No download folder selected" else "Folder selected ✓")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = pickTree) { Text("Choose folder") }
        }
        OutlinedTextField(
            value = s.storageTemplate,
            onValueChange = { v -> scope.launch { container.settingsRepository.update { it.copy(storageTemplate = v) } } },
            label = { Text("Path template") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        SettingsHeader("Network")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NetworkPolicy.entries.forEach { p ->
                FilterChip(
                    selected = s.networkPolicy == p,
                    onClick = { scope.launch { container.settingsRepository.update { it.copy(networkPolicy = p) } } },
                    label = { Text(p.name.lowercase()) },
                )
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Download Now overrides network policy", Modifier.weight(1f))
            Switch(checked = s.downloadNowOverridesNetwork, onCheckedChange = { v -> scope.launch { container.settingsRepository.update { it.copy(downloadNowOverridesNetwork = v) } } })
        }

        SettingsHeader("Speed & Concurrency")
        Text("Speed limit: " + (s.globalSpeedLimitBps.takeIf { it > 0 }?.let { "${it / 1024} KB/s" } ?: "unlimited"))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = s.concurrencyMode == "AUTO", onClick = { scope.launch { container.settingsRepository.update { it.copy(concurrencyMode = "AUTO") } } }, label = { Text("Auto") })
            FilterChip(selected = s.concurrencyMode == "FIXED", onClick = { scope.launch { container.settingsRepository.update { it.copy(concurrencyMode = "FIXED") } } }, label = { Text("Fixed") })
            if (s.concurrencyMode == "FIXED") (1..5).forEach { n ->
                FilterChip(selected = s.fixedConcurrency == n, onClick = { scope.launch { container.settingsRepository.update { it.copy(fixedConcurrency = n) } } }, label = { Text("$n") })
            }
        }
        Text("Max concurrency (auto cap): ${s.maxConcurrency}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Slider(value = s.maxConcurrency.toFloat(), onValueChange = { v -> scope.launch { container.settingsRepository.update { it.copy(maxConcurrency = v.toInt()) } } }, valueRange = 1f..8f, steps = 6)

        SettingsHeader("Notifications")
        LabeledSwitch("Notify on file complete", s.notificationsOnComplete) { v -> scope.launch { container.settingsRepository.update { it.copy(notificationsOnComplete = v) } } }
        SettingsHeader("Background Reliability")
        LabeledSwitch("Dark mode", s.darkMode) { v -> scope.launch { container.settingsRepository.update { it.copy(darkMode = v) } } }
        LabeledSwitch("Watchdog service", s.reliabilityWatchdogEnabled) { v -> scope.launch { container.settingsRepository.update { it.copy(reliabilityWatchdogEnabled = v) } } }
        LabeledSwitch("Shizuku enhancements (optional)", s.reliabilityShizukuEnabled) { v -> scope.launch { container.settingsRepository.update { it.copy(reliabilityShizukuEnabled = v) } } }
        LabeledSwitch("Start engine after boot", s.autoStartAfterBoot) { v -> scope.launch { container.settingsRepository.update { it.copy(autoStartAfterBoot = v) } } }
        SettingsHeader("Advanced")
        Text("Each account uses an isolated TDLib session directory. Sessions are never exported or backed up.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountRow(account: AccountEntity, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(account.displayName.ifBlank { "Telegram account" })
                Text(account.phoneNumber, style = MaterialTheme.typography.labelSmall)
            }
            if (!selected) TextButton(onClick = onSelect) { Text("Switch") }
            TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    HorizontalDivider()
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
