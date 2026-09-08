package com.tdm.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdm.app.core.stats.PredictionEngine
import com.tdm.app.di.AppContainer
import com.tdm.app.ui.components.EmptyState
import com.tdm.app.ui.components.Format

/**
 * Preview / Dry Run (spec §21): what the NEXT session would download — NO network activity.
 * Pure computation over DB + stats.
 */
@Composable
fun PreviewScreen(container: AppContainer, onBack: () -> Unit) {
    val queued = container.database.taskDao().observeQueue().collectAsState(initial = emptyList()).value
        .filter { it.status == com.tdm.app.core.model.TaskStatus.QUEUED }
    var forecast by remember { mutableStateOf<PredictionEngine.Forecast?>(null) }
    var windowName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(queued.size) {
        // remaining window (or a synthetic 2h estimate when no schedule)
        val profiles = container.database.scheduleDao().profiles().filter { it.enabled }
        var remaining = 7200L
        for (p in profiles) {
            val ws = container.database.scheduleDao().windows(p.id).map {
                com.tdm.app.core.scheduler.ScheduleMatcher.WindowRef(it.daysBitmask, it.startMinuteOfDay, it.endMinuteOfDay)
            }
            val rem = com.tdm.app.core.scheduler.ScheduleMatcher.remainingSeconds(ws)
            if (rem > 0) { remaining = rem; windowName = p.name; break }
        }
        val histRows = container.database.statisticsDao().since(com.tdm.app.core.stats.StatisticsEngine.daysAgo(7))
        val samples = histRows.sumOf { it.speedSampleCount }
        val histSpeed = if (samples == 0) 0.0 else histRows.sumOf { it.sumSpeedSamplesBps } / samples
        forecast = PredictionEngine.forecast(
            queued = queued,
            availableSeconds = remaining,
            historicalSpeedBps = histSpeed,
            concurrency = 3,
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            Text("Preview — next session", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("← Back") }
        }
        val f = forecast
        if (queued.isEmpty()) {
            EmptyState("Queue is empty — nothing to preview.")
        } else {
            Row(Modifier.padding(horizontal = 8.dp)) {
                com.tdm.app.ui.components.StatCard("Available", Format.eta(f?.availableSeconds ?: 0), Modifier.weight(1f))
                com.tdm.app.ui.components.StatCard("Expected data", Format.bytes(f?.expectedBytes ?: 0), Modifier.weight(1f))
                com.tdm.app.ui.components.StatCard("Likely done", "${f?.expectedCompletedFiles ?: 0}/${queued.size}", Modifier.weight(1f))
            }
            Text(
                "Window: ${windowName ?: "estimate (no active schedule)"} • expected ${Format.speed(f?.expectedSpeedBps ?: 0.0)} — approximate, not a promise (spec §60)",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(12.dp),
            )
            LazyColumn {
                items(queued, key = { it.id }) { t ->
                    val eta = f?.etaPerFileSec?.get(t.id) ?: -1L
                    val willComplete = f?.filesLikelyComplete?.contains(t.id) == true
                    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Row(Modifier.padding(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t.filename, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(
                                    "#${t.id} • ${Format.bytes(t.size)}" +
                                        (if (t.downloadedBytes > 0) " (resume ${Format.bytes(t.downloadedBytes)})" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Text(
                                "ETA ${Format.eta(eta)}" + if (willComplete) " ✓" else " ⚠",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (willComplete) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
