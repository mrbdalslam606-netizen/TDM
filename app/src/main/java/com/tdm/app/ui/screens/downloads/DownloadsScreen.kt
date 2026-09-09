package com.tdm.app.ui.screens.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdm.app.core.model.EngineRunState
import com.tdm.app.core.model.TaskPriority
import com.tdm.app.core.model.TaskStatus
import com.tdm.app.di.AppContainer
import com.tdm.app.ui.components.*
import com.tdm.app.ui.theme.Accent
import kotlinx.coroutines.launch

/**
 * Downloads screen (spec §46, §47): current file, speed, ETA, active count, queue count,
 * next schedule, per-task controls, Pause/Resume All, speed limit, concurrency, Preview.
 */
@Composable
fun DownloadsScreen(container: AppContainer, onReliability: () -> Unit, onPreview: () -> Unit) {
    val scope = rememberCoroutineScope()
    val engine = container.engineInstanceOrNull()
    val snap = container.engineState?.snapshot?.collectAsState()?.value
    val live = container.engineState?.liveProgress?.collectAsState()?.value ?: emptyMap()
    val tasks = container.database.taskDao().observeQueue().collectAsState(initial = emptyList()).value
    val settings = container.settingsRepository.settings.collectAsState(initial = null).value
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showPriorityFor by remember { mutableStateOf<Long?>(null) }
    var showLinkDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {

        // ---- status banner (spec §47: key info without navigating) ----
        Card(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (snap?.runState) {
                            EngineRunState.RUNNING_SCHEDULE -> "Running (schedule)"
                            EngineRunState.RUNNING_MANUAL -> "Running (manual)"
                            EngineRunState.PAUSED_ALL -> "Paused (all)"
                            EngineRunState.WAITING_SCHEDULE -> "Waiting for schedule window"
                            EngineRunState.WAITING_NETWORK -> "Waiting for network"
                            EngineRunState.RECOVERY -> "Recovering…"
                            EngineRunState.RECOVERY_FAILED -> "Recovery FAILED — action needed"
                            EngineRunState.STOPPED -> "Engine stopped"
                            else -> "Idle"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (snap?.runState == EngineRunState.RECOVERY_FAILED)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPreview) { Text("Preview") }
                    TextButton(onClick = onReliability) { Text("Reliability") }
                    TextButton(onClick = { showLinkDialog = true }) { Text("Add Link") }
                }
                Row {
                    Text("Speed: ${Format.speed(snap?.totalSpeedBps ?: 0.0)}", modifier = Modifier.weight(1f))
                    Text("Active: ${snap?.activeCount ?: 0}   Queue: ${snap?.queuedCount ?: 0}", modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 4.dp)) {
                    Text(
                        "Schedule: ${snap?.currentScheduleName ?: "none"}" +
                            (snap?.nextScheduleAt?.let { "  (next ${Format.time(it)})" } ?: ""),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "Network: ${settings?.networkPolicy ?: "ANY"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // ---- global controls (spec §47) ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { scope.launch { engine?.pauseAll() } }, modifier = Modifier.weight(1f)) { Text("Pause All") }
            Button(onClick = { scope.launch { engine?.resumeAll() } }, modifier = Modifier.weight(1f)) { Text("Resume All") }
            OutlinedButton(onClick = { showSpeedDialog = true }, modifier = Modifier.weight(1f)) {
                Text(
                    "Speed: " + (settings?.globalSpeedLimitBps?.takeIf { it > 0 }
                        ?.let { Format.speed(it.toDouble()) } ?: "∞")
                )
            }
        }

        // ---- task list ----
        if (tasks.isEmpty()) {
            EmptyState("No active or queued downloads.\nFiles from monitored sources and the Inbox appear here.")
        } else {
            LazyColumn {
                items(tasks, key = { it.id }) { t ->
                    val p = live[t.id]
                    TaskRow(
                        filename = t.filename,
                        status = p?.status ?: t.status,
                        downloaded = p?.downloadedBytes ?: t.downloadedBytes,
                        total = p?.totalBytes ?: t.size,
                        speedBps = p?.speedBps ?: 0.0,
                        etaSec = p?.etaSec ?: -1,
                        error = t.lastError,
                        phase = p?.phase ?: "",
                    ) {
                        TextButton(onClick = { scope.launch {
                            when (t.status) {
                                TaskStatus.DOWNLOADING, TaskStatus.STARTING, TaskStatus.QUEUED ->
                                    engine?.pauseTask(t.id, manual = true)
                                TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.RETRY_WAIT ->
                                    engine?.resumeTask(t.id)
                                else -> Unit
                            }
                        } }) {
                            Text(
                                when (t.status) {
                                    TaskStatus.DOWNLOADING, TaskStatus.STARTING, TaskStatus.QUEUED -> "Pause"
                                    else -> "Resume"
                                }
                            )
                        }
                        TextButton(onClick = { scope.launch { engine?.downloadNow(t.id) } }) { Text("Now") }
                        TextButton(onClick = { showPriorityFor = t.id }) { Text("Prio") }
                        TextButton(onClick = { scope.launch { engine?.cancelTask(t.id) } }) { Text("✕") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showSpeedDialog && settings != null) {
        SpeedLimitDialog(container, onDismiss = { showSpeedDialog = false })
    }
    showPriorityFor?.let { taskId ->
        PriorityDialog(onDismiss = { showPriorityFor = null }) { prio ->
            scope.launch { engine?.setPriority(taskId, prio) }
            showPriorityFor = null
        }
    }
    if (showLinkDialog) DirectLinkDialog(container, onDismiss = { showLinkDialog = false })
}

@Composable
private fun DirectLinkDialog(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val profiles = container.database.scheduleDao().observeProfiles().collectAsState(initial = emptyList()).value
    var link by remember { mutableStateOf("") }
    var immediate by remember { mutableStateOf(true) }
    var profileId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Telegram file link") },
        text = {
            Column {
                OutlinedTextField(
                    value = link, onValueChange = { link = it },
                    label = { Text("Telegram message link") },
                    placeholder = { Text("https://t.me/channel/123") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Download immediately", modifier = Modifier.weight(1f))
                    Switch(checked = immediate, onCheckedChange = { immediate = it })
                }
                if (!immediate) {
                    Text("Schedule profile", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        profiles.forEach { p ->
                            FilterChip(selected = profileId == p.id, onClick = { profileId = p.id }, label = { Text(p.name) })
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(enabled = link.isNotBlank() && (immediate || profileId != null), onClick = {
                scope.launch {
                    runCatching {
                        val resolved = container.telegram.resolveTelegramLink(link)
                            ?: throw IllegalStateException("Link is invalid or inaccessible")
                        val message = resolved.message
                            ?: throw IllegalStateException("Use a message link that points to a file")
                        val taskId = container.monitorInstance()?.enqueueDirectLink(message, if (immediate) null else profileId)
                            ?: throw IllegalStateException("Download monitor is unavailable")
                        if (immediate && taskId > 0) container.engineInstanceOrNull()?.downloadNow(taskId)
                    }.onFailure { error = it.message ?: "Unable to add link" }
                        .onSuccess { onDismiss() }
                }
            }) { Text("Add to downloads") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SpeedLimitDialog(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current = container.settingsRepository.settings.collectAsState(initial = null).value
    var text by remember { mutableStateOf(current?.globalSpeedLimitBps?.takeIf { it > 0 }?.div(1024)?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speed limit (KB/s)") },
        text = {
            Column {
                Text("Empty = unlimited. Applied live, no restart needed.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it.filter { c -> c.isDigit() } })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("500", "2048", "5120").forEach { preset ->
                        AssistChip(onClick = { text = preset }, label = { Text(preset) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val bps = text.toLongOrNull()?.times(1024) ?: 0L
                    container.settingsRepository.update { it.copy(globalSpeedLimitBps = bps) }
                    container.engineInstanceOrNull()?.setSpeedLimitBps(bps)
                }
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PriorityDialog(onDismiss: () -> Unit, onPick: (TaskPriority) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Priority") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TaskPriority.entries.forEach { p ->
                    AssistChip(onClick = { onPick(p) }, label = { Text(p.name) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
