package com.tdm.app.ui.screens.schedules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdm.app.core.scheduler.ScheduleMatcher
import com.tdm.app.data.db.NetworkPolicy
import com.tdm.app.data.db.ScheduleProfileEntity
import com.tdm.app.data.db.ScheduleWindowEntity
import com.tdm.app.di.AppContainer
import com.tdm.app.ui.components.EmptyState
import com.tdm.app.ui.components.Format
import kotlinx.coroutines.launch

/** Schedules screen (spec §7, §8, §49): profiles with multiple windows, days, limits, end behavior. */
@Composable
fun SchedulesScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val profiles = container.database.scheduleDao().observeProfiles().collectAsState(initial = emptyList()).value
    var editing by remember { mutableStateOf<ScheduleProfileEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Button(
            onClick = { adding = true },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) { Text("New Schedule Profile") }

        if (profiles.isEmpty()) EmptyState("No schedule profiles. Without a schedule, queued files download any time.")
        else LazyColumn {
            items(profiles, key = { it.id }) { p ->
                val windows = container.database.scheduleDao().observeWindows(p.id)
                    .collectAsState(initial = emptyList()).value
                Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${p.networkPolicy.name} • " +
                                        (if (p.autoConcurrency) "Auto concurrency" else "Fixed ${p.maxConcurrency}") +
                                        " • " + (if (p.speedLimitBps > 0) Format.speed(p.speedLimitBps.toDouble()) else "no speed limit"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = p.enabled, onCheckedChange = { on ->
                                scope.launch { container.database.scheduleDao().upsertProfile(p.copy(enabled = on)) }
                            })
                        }
                        windows.forEach { w ->
                            Text(
                                "  ${dayNames(w.daysBitmask)}: ${Format.mmss(w.startMinuteOfDay)} – ${Format.mmss(w.endMinuteOfDay)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            if (p.endBehavior == "FINISH_CURRENT") "End of window: finish current file"
                            else "End of window: stop immediately",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row {
                            TextButton(onClick = { editing = p }) { Text("Edit") }
                            TextButton(onClick = { scope.launch {
                                container.database.scheduleDao().deleteProfile(p.id)
                            } }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (adding) ProfileDialog(container, onDismiss = { adding = false })
    editing?.let { ProfileDialog(container, existing = it, onDismiss = { editing = null }) }
}

private fun dayNames(mask: Int): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val days = names.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
    return if (days.size == 7) "Daily" else days.joinToString(", ").ifBlank { "—" }
}

@Composable
private fun ProfileDialog(container: AppContainer, existing: ScheduleProfileEntity? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var network by remember { mutableStateOf(existing?.networkPolicy ?: NetworkPolicy.ANY) }
    var auto by remember { mutableStateOf(existing?.autoConcurrency ?: true) }
    var maxConc by remember { mutableStateOf((existing?.maxConcurrency?.takeIf { it > 0 } ?: 3).toString()) }
    var speedLimit by remember { mutableStateOf("") }
    var finishCurrent by remember { mutableStateOf(existing?.endBehavior == "FINISH_CURRENT") }

    // window editor state (multiple windows supported)
    var windows by remember {
        mutableStateOf(
            existing?.let { p ->
                kotlinx.coroutines.runBlocking { container.database.scheduleDao().windows(p.id) }
            } ?: emptyList()
        )
    }
    var startMin by remember { mutableStateOf(360) }  // 06:00
    var endMin by remember { mutableStateOf(540) }    // 09:00
    var days by remember { mutableStateOf(ScheduleMatcher.ALL_DAYS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Profile" else "Edit Profile") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                    NetworkPolicy.entries.forEach { p ->
                        FilterChip(selected = network == p, onClick = { network = p }, label = { Text(p.name.lowercase()) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Auto concurrency", Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
                if (!auto) {
                    OutlinedTextField(
                        value = maxConc, onValueChange = { maxConc = it.filter { c -> c.isDigit() } },
                        label = { Text("Fixed concurrency") },
                    )
                }
                OutlinedTextField(
                    value = speedLimit, onValueChange = { speedLimit = it.filter { c -> c.isDigit() } },
                    label = { Text("Speed limit KB/s (empty = unlimited)") },
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("End of window: finish current file", Modifier.weight(1f))
                    Switch(checked = finishCurrent, onCheckedChange = { finishCurrent = it })
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Windows", style = MaterialTheme.typography.titleSmall)
                windows.forEachIndexed { i, w ->
                    Row {
                        Text(
                            "${Format.mmss(w.startMinuteOfDay)}–${Format.mmss(w.endMinuteOfDay)} (${dayNames(w.daysBitmask)})",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { windows = windows.filterIndexed { j, _ -> j != i } }) { Text("✕") }
                    }
                }
                Text("Start (HH:MM)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                var sMin by remember { mutableStateOf("360") }
                var eMin by remember { mutableStateOf("540") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(sMin, { sMin = it.filter { c -> c.isDigit() } },
                        label = { Text("start min") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(eMin, { eMin = it.filter { c -> c.isDigit() } },
                        label = { Text("end min") }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val s = sMin.toIntOrNull() ?: return@Button
                        val e = eMin.toIntOrNull() ?: return@Button
                        windows = windows + ScheduleWindowEntity(
                            profileId = existing?.id ?: 0,
                            daysBitmask = days, startMinuteOfDay = s.coerceIn(0, 1439),
                            endMinuteOfDay = e.coerceIn(0, 1439),
                        )
                    }) { Text("Add") }
                }
                Text(
                    "Days: Mon..Sun — set days bitmask ${ScheduleMatcher.ALL_DAYS} = daily by default",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, d ->
                        FilterChip(
                            selected = (days shr i) and 1 == 1,
                            onClick = { days = days xor (1 shl i) },
                            label = { Text(d) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val id = container.database.scheduleDao().upsertProfile(
                        (existing ?: ScheduleProfileEntity(name = name)).copy(
                            name = name.ifBlank { "Schedule" },
                            networkPolicy = network,
                            autoConcurrency = auto,
                            maxConcurrency = maxConc.toIntOrNull() ?: 0,
                            speedLimitBps = (speedLimit.toLongOrNull() ?: 0L) * 1024,
                            endBehavior = if (finishCurrent) "FINISH_CURRENT" else "STOP_IMMEDIATELY",
                        )
                    )
                    container.database.scheduleDao().deleteWindowsOf(id)
                    windows.forEach { w -> container.database.scheduleDao().upsertWindow(w.copy(profileId = id)) }
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
