package com.tdm.app.ui.screens.sources

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdm.app.core.model.TaskPriority
import com.tdm.app.data.db.FileFilter
import com.tdm.app.data.db.NetworkPolicy
import com.tdm.app.data.db.QueueMode
import com.tdm.app.data.db.SourceEntity
import com.tdm.app.data.db.SourceType
import com.tdm.app.data.db.StartFromMode
import com.tdm.app.di.AppContainer
import com.tdm.app.ui.components.EmptyState
import kotlinx.coroutines.launch

/** Sources screen (spec §4, §5, §48): monitored channels + per-source settings + Scan/Download Now. */
@Composable
fun SourcesScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val sources = container.database.sourceDao().observeAll().collectAsState(initial = emptyList()).value
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceEntity?>(null) }
    val monitor = container.monitorInstance()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { adding = true }, modifier = Modifier.weight(1f)) { Text("Add Source") }
            OutlinedButton(onClick = {
                scope.launch {
                    com.tdm.app.core.logging.LogRepo.log(container.database, "SCHEDULER", "INFO", "manual scan started")
                    monitor?.scanAll()
                }
            }, modifier = Modifier.weight(1f)) { Text("Scan Now") }
        }

        if (sources.isEmpty()) EmptyState("No sources yet. Add a channel/group or use the Inbox.")
        else LazyColumn {
            items(sources, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${s.type.name} • ${s.queueMode.name} • ${s.networkPolicy.name}" +
                                        (s.scheduleProfileId?.let { " • schedule#$it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = s.enabled, onCheckedChange = { on ->
                                scope.launch {
                                    container.database.sourceDao().upsert(s.copy(enabled = on))
                                }
                            })
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editing = s }) { Text("Settings") }
                            TextButton(onClick = { scope.launch { monitor?.scanSource(s) } }) { Text("Scan") }
                            TextButton(onClick = { scope.launch {
                                container.database.taskDao().activeAndQueued()
                                    .filter { it.sourceId == s.id && it.status == com.tdm.app.core.model.TaskStatus.QUEUED }
                                    .forEach { container.engineInstanceOrNull()?.downloadNow(it.id) }
                            } }) { Text("Download Now") }
                            TextButton(onClick = { scope.launch { container.database.sourceDao().delete(s.id) } }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) SourceDialog(container, templateId = null, onDismiss = { adding = false })
    editing?.let { s ->
        SourceDialog(container, templateId = null, existing = s, onDismiss = { editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceDialog(
    container: AppContainer,
    templateId: Long?,
    existing: SourceEntity? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val templates = container.database.sourceTemplateDao().observeAll().collectAsState(initial = emptyList()).value
    val profiles = container.database.scheduleDao().observeProfiles().collectAsState(initial = emptyList()).value

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var handle by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(existing?.type ?: SourceType.CHANNEL) }
    var autoDownload by remember { mutableStateOf(existing?.autoDownload ?: true) }
    var monitoring by remember { mutableStateOf(existing?.monitoringEnabled ?: true) }
    var queueMode by remember { mutableStateOf(existing?.queueMode ?: QueueMode.FIFO) }
    var priority by remember { mutableStateOf(existing?.priority ?: TaskPriority.NORMAL) }
    var network by remember { mutableStateOf(existing?.networkPolicy ?: NetworkPolicy.ANY) }
    var profileId by remember { mutableStateOf(existing?.scheduleProfileId) }
    var video by remember { mutableStateOf(existing?.filter?.video ?: true) }
    var documents by remember { mutableStateOf(existing?.filter?.documents ?: true) }
    var archives by remember { mutableStateOf(existing?.filter?.archives ?: true) }
    var images by remember { mutableStateOf(existing?.filter?.images ?: false) }
    var startFrom by remember { mutableStateOf(existing?.startFromMode ?: StartFromMode.NOW) }
    var startValue by remember { mutableStateOf(existing?.startFromValue ?: "") }
    var storageTpl by remember { mutableStateOf(existing?.storageTemplate ?: "{root}/{channel}/{date}/{filename}") }
    var applied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Source" else "Edit Source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (existing == null && !applied) {
                    // Template selection at creation (spec §5-6) — applied ONCE, then independent
                    Text("Start from template (optional)", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !applied && templateId == null,
                            onClick = {}, label = { Text("Blank") })
                        templates.forEach { t ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    // apply template values into the form (independent copy)
                                    autoDownload = t.autoDownload; monitoring = t.monitoringEnabled
                                    queueMode = t.queueMode; priority = t.priority
                                    network = t.networkPolicy; profileId = t.scheduleProfileId
                                    video = t.filter.video; documents = t.filter.documents
                                    archives = t.filter.archives; images = t.filter.images
                                    storageTpl = t.storageTemplate
                                    applied = true
                                },
                                label = { Text(t.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") })
                if (existing == null) {
                    OutlinedTextField(
                        value = handle, onValueChange = { handle = it },
                        label = { Text("Telegram username, message, topic, or invite link") },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { autoDownload = !autoDownload },
                        label = { Text(if (autoDownload) "Auto-DL: on" else "Auto-DL: off") })
                    AssistChip(onClick = { monitoring = !monitoring },
                        label = { Text(if (monitoring) "Monitor: on" else "Monitor: off") })
                }
                Text("Queue mode", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QueueMode.entries.forEach { m ->
                        FilterChip(selected = queueMode == m, onClick = { queueMode = m }, label = { Text(m.name.lowercase()) })
                    }
                }
                Text("Network", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NetworkPolicy.entries.forEach { p ->
                        FilterChip(selected = network == p, onClick = { network = p }, label = { Text(p.name.lowercase()) })
                    }
                }
                Text("Priority", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TaskPriority.entries.forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.name) })
                    }
                }
                Text("File types", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip(onClick = { video = !video }, label = { Text(if (video) "✓ Video" else "Video") })
                    AssistChip(onClick = { documents = !documents }, label = { Text(if (documents) "✓ Docs" else "Docs") })
                    AssistChip(onClick = { archives = !archives }, label = { Text(if (archives) "✓ Archives" else "Arch") })
                    AssistChip(onClick = { images = !images }, label = { Text(if (images) "✓ Images" else "Img") })
                }
                if (existing == null) {
                    Text("Initial scan", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        StartFromMode.entries.forEach { m ->
                            FilterChip(selected = startFrom == m, onClick = { startFrom = m },
                                label = { Text(m.name.lowercase().replace('_', ' ')) })
                        }
                    }
                    if (startFrom != StartFromMode.NOW && startFrom != StartFromMode.ALL) {
                        OutlinedTextField(
                            value = startValue, onValueChange = { startValue = it },
                            label = {
                                Text(
                                    when (startFrom) {
                                        StartFromMode.LAST_N -> "N (e.g. 50)"
                                        StartFromMode.SINCE_DATE -> "Since (unix seconds)"
                                        else -> "minId-maxId"
                                    }
                                )
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = storageTpl, onValueChange = { storageTpl = it },
                    label = { Text("Storage template") },
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text("Schedule profile", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = profileId == null, onClick = { profileId = null }, label = { Text("None") })
                    profiles.forEach { p ->
                        FilterChip(selected = profileId == p.id, onClick = { profileId = p.id }, label = { Text(p.name) })
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    runCatching {
                        if (existing == null) {
                            val chat = container.telegram.resolveSourceLink(handle)
                                ?: throw IllegalStateException(
                                    "Telegram link is invalid, inaccessible, or the message could not be found: $handle"
                                )
                            val s = SourceEntity(
                                name = name.ifBlank { chat.title },
                                type = when (chat.type) {
                                    "CHANNEL" -> SourceType.CHANNEL
                                    "SUPERGROUP", "GROUP" -> SourceType.GROUP
                                    else -> SourceType.CHAT
                                },
                                chatId = chat.id,
                                enabled = true,
                                monitoringEnabled = monitoring,
                                autoDownload = autoDownload,
                                filter = FileFilter(video, documents, archives, images),
                                scheduleProfileId = profileId,
                                queueMode = queueMode,
                                priority = priority,
                                storageTemplate = storageTpl,
                                networkPolicy = network,
                                startFromMode = startFrom,
                                startFromValue = startValue.trim(),
                            )
                            container.database.sourceDao().upsert(s)
                        } else {
                            container.database.sourceDao().upsert(
                                existing.copy(
                                    name = name, monitoringEnabled = monitoring, autoDownload = autoDownload,
                                    queueMode = queueMode, priority = priority, networkPolicy = network,
                                    scheduleProfileId = profileId, storageTemplate = storageTpl,
                                    filter = FileFilter(video, documents, archives, images),
                                )
                            )
                        }
                    }.onFailure { error = it.message ?: "error" }.onSuccess { onDismiss() }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
