package com.tdm.app.ui.screens.templates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdm.app.core.model.TaskPriority
import com.tdm.app.data.db.NetworkPolicy
import com.tdm.app.data.db.QueueMode
import com.tdm.app.data.db.SourceTemplateEntity
import com.tdm.app.di.AppContainer
import com.tdm.app.ui.components.EmptyState
import kotlinx.coroutines.launch

/** Templates screen (spec §6): reusable source configs — copied at apply time, fully independent after. */
@Composable
fun TemplatesScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val templates = container.database.sourceTemplateDao().observeAll().collectAsState(initial = emptyList()).value
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("New Source Template")
        }
        if (templates.isEmpty()) {
            EmptyState("No templates. Example: 'Anime Default' — videos+archives, normal priority, unlimited retries.")
        } else LazyColumn {
            items(templates, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${t.queueMode.name} • ${t.priority.name} • ${t.networkPolicy.name}" +
                                (if (t.retryPolicy.unlimited) " • retry ∞" else " • retry ${t.retryPolicy.maxAttempts}"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = { scope.launch {
                                container.database.sourceTemplateDao().delete(t.id)
                            } }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (adding) TemplateDialog(container, onDismiss = { adding = false })
}

@Composable
private fun TemplateDialog(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var queueMode by remember { mutableStateOf(QueueMode.FILENAME_NATURAL) }
    var priority by remember { mutableStateOf(TaskPriority.NORMAL) }
    var network by remember { mutableStateOf(NetworkPolicy.ANY) }
    var unlimitedRetry by remember { mutableStateOf(true) }
    var video by remember { mutableStateOf(true) }
    var archives by remember { mutableStateOf(true) }
    var documents by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Source Template") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Text("Queue mode", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QueueMode.entries.forEach { m ->
                        FilterChip(selected = queueMode == m, onClick = { queueMode = m },
                            label = { Text(m.name.lowercase().replace('_', ' ')) })
                    }
                }
                Text("Priority", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TaskPriority.entries.forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.name) })
                    }
                }
                Text("Network", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NetworkPolicy.entries.forEach { p ->
                        FilterChip(selected = network == p, onClick = { network = p }, label = { Text(p.name.lowercase()) })
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Unlimited retries", Modifier.weight(1f))
                    Switch(checked = unlimitedRetry, onCheckedChange = { unlimitedRetry = it })
                }
                Text("File types", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip(onClick = { video = !video }, label = { Text(if (video) "✓ Video" else "Video") })
                    AssistChip(onClick = { archives = !archives }, label = { Text(if (archives) "✓ Arch" else "Arch") })
                    AssistChip(onClick = { documents = !documents }, label = { Text(if (documents) "✓ Docs" else "Docs") })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    container.database.sourceTemplateDao().upsert(
                        SourceTemplateEntity(
                            name = name.ifBlank { "Template" },
                            queueMode = queueMode,
                            priority = priority,
                            networkPolicy = network,
                            filter = com.tdm.app.data.db.FileFilter(
                                video = video, archives = archives, documents = documents, images = false
                            ),
                            retryPolicy = com.tdm.app.data.db.RetryPolicy(unlimited = unlimitedRetry),
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
