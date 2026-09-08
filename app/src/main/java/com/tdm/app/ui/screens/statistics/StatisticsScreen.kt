package com.tdm.app.ui.screens.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdm.app.core.stats.StatisticsEngine
import com.tdm.app.di.AppContainer
import com.tdm.app.ui.components.Format
import com.tdm.app.ui.components.StatCard
import kotlinx.coroutines.launch

/** Statistics screen (spec §44): today/yesterday/7d/30d/all-time + session history. */
@Composable
fun StatisticsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf("Today") }
    var stats by remember { mutableStateOf<StatisticsEngine.RangeStats?>(null) }
    val sessions = container.database.sessionDao().observeRecent(50).collectAsState(initial = emptyList()).value

    LaunchedEffect(selected) {
        val dao = container.database.statisticsDao()
        stats = when (selected) {
            "Today" -> StatisticsEngine.RangeStats(0, 0, 0, 0, 0.0, 0.0, 0).let {
                container.database.statisticsDao().let { _ -> runRange(container, StatisticsEngine.localDayStart()) }
            }
            "Yesterday" -> runRange(container, StatisticsEngine.localDayStart(System.currentTimeMillis() - 86_400_000), 1)
            "Last 7 days" -> runRange(container, StatisticsEngine.daysAgo(6))
            "Last 30 days" -> runRange(container, StatisticsEngine.daysAgo(29))
            else -> runRange(container, 0)
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Today", "Yesterday", "Last 7 days", "Last 30 days", "All time").forEach { f ->
                    FilterChip(selected = selected == f, onClick = { selected = f }, label = { Text(f) })
                }
            }
        }
        item {
            val s = stats
            Row(Modifier.padding(horizontal = 8.dp)) {
                StatCard("Downloaded", Format.bytes(s?.bytes ?: 0), Modifier.weight(1f))
                StatCard("Files", "${s?.files ?: 0}", Modifier.weight(1f))
                StatCard("Failed", "${s?.failed ?: 0}", Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                StatCard("Avg speed", Format.speed(s?.avgSpeedBps ?: 0.0), Modifier.weight(1f))
                StatCard("Peak", Format.speed(s?.peakSpeedBps ?: 0.0), Modifier.weight(1f))
                StatCard("Retries", "${s?.retries ?: 0}", Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                StatCard("Download time", Format.eta((s?.activeMs ?: 0L) / 1000), Modifier.weight(1f))
                StatCard("Sessions", "${sessions.size}", Modifier.weight(1f))
            }
        }
        item {
            Text(
                "Session history", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        items(sessions, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${s.trigger.lowercase().replaceFirstChar { it.uppercase() }} — ${Format.time(s.startedAt)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Downloaded: ${Format.bytes(s.bytes)} • Files: ${s.filesCompleted}/${s.filesStarted}" +
                            (if (s.filesFailed > 0) " (failed ${s.filesFailed})" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Avg: ${Format.speed(s.avgSpeedBps)} • Peak: ${Format.speed(s.peakSpeedBps)} • Concurrency: ${"%.1f".format(s.avgConcurrency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private suspend fun runRange(container: AppContainer, fromDay: Long, days: Int = Int.MAX_VALUE): StatisticsEngine.RangeStats {
    val stats = container.database.statisticsDao().since(fromDay)
    return StatisticsEngine.RangeStats(
        bytes = stats.sumOf { it.bytesCompleted },
        files = stats.sumOf { it.filesCompleted },
        failed = stats.sumOf { it.filesFailed },
        retries = stats.sumOf { it.retryCount },
        avgSpeedBps = if (stats.sumOf { it.speedSampleCount } == 0) 0.0
        else stats.sumOf { it.sumSpeedSamplesBps } / stats.sumOf { it.speedSampleCount },
        peakSpeedBps = stats.maxOfOrNull { it.peakSpeedBps } ?: 0.0,
        activeMs = stats.sumOf { it.activeDownloadMs },
    )
}
