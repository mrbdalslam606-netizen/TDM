package com.tdm.app.ui.screens.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdm.app.core.model.TaskStatus
import com.tdm.app.di.AppContainer
import com.tdm.app.ui.components.EmptyState
import com.tdm.app.ui.components.Format
import com.tdm.app.ui.components.TaskRow
import kotlinx.coroutines.launch

/** History (spec §43): Completed / Failed / Canceled / Paused / All + Retry buttons. */
@Composable
fun HistoryScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("ALL") }
    val tasks = container.database.taskDao().observeRecent(500).collectAsState(initial = emptyList()).value

    val filtered = tasks.filter {
        when (filter) {
            "Completed" -> it.status == TaskStatus.COMPLETED
            "Failed" -> it.status == TaskStatus.FAILED || it.status == TaskStatus.RETRY_WAIT
            "Canceled" -> it.status == TaskStatus.CANCELED
            "Paused" -> it.status == TaskStatus.PAUSED || it.status == TaskStatus.INSUFFICIENT_STORAGE
            else -> true
        }
    }.sortedByDescending { it.createdAt }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("All", "Completed", "Failed", "Canceled", "Paused").forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (filtered.isEmpty()) EmptyState("Nothing here yet.")
        else LazyColumn {
            items(filtered, key = { it.id }) { t ->
                TaskRow(
                    filename = t.filename,
                    status = t.status,
                    downloaded = t.downloadedBytes,
                    total = t.size,
                    speedBps = t.averageSpeedBps,
                    etaSec = -1,
                    error = t.lastError,
                ) {
                    Text(t.sourceId.toString().let { "src $it" }, style = MaterialTheme.typography.labelSmall)
                    if (t.status in setOf(TaskStatus.FAILED, TaskStatus.RETRY_WAIT)) {
                        TextButton(onClick = { scope.launch { container.engineInstanceOrNull()?.retryTask(t.id, now = true) } }) {
                            Text("Retry Now")
                        }
                    }
                    if (t.status == TaskStatus.RETRY_WAIT && t.nextRetryAt > 0) {
                        Text(
                            "next: ${Format.time(t.nextRetryAt)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (t.status == TaskStatus.COMPLETED) {
                        Text(
                            "${Format.time(t.completedAt)} • ${t.retryCount} retries",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { scope.launch { container.database.taskDao().delete(t.id) } }) {
                        Text("Remove")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
