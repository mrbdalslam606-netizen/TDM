package com.tdm.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tdm.app.core.model.TaskStatus
import com.tdm.app.ui.theme.StatusAmber
import com.tdm.app.ui.theme.StatusGreen
import com.tdm.app.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting helpers — one consistent presentation across all screens. */
object Format {
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    fun speed(bps: Double): String = bytes(bps.toLong()) + "/s"

    fun eta(sec: Long): String = when {
        sec < 0 -> "—"
        sec < 60 -> "${sec}s"
        sec < 3600 -> String.format(Locale.US, "%dm %02ds", sec / 60, sec % 60)
        sec < 86400 -> String.format(Locale.US, "%dh %02dm", sec / 3600, (sec % 3600) / 60)
        else -> String.format(Locale.US, "%dd %dh", sec / 86400, (sec % 86400) / 3600)
    }

    fun time(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    fun mmss(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
}

@Composable
fun statusColor(s: TaskStatus): Color = when (s) {
    TaskStatus.COMPLETED -> StatusGreen
    TaskStatus.FAILED, TaskStatus.CANCELED -> StatusRed
    TaskStatus.RETRY_WAIT, TaskStatus.PAUSED, TaskStatus.PAUSING,
    TaskStatus.INSUFFICIENT_STORAGE, TaskStatus.RECOVERY_PENDING -> StatusAmber
    TaskStatus.DOWNLOADING, TaskStatus.STARTING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Task row: filename, progress bar, downloaded/total, speed, ETA, status chip, actions. */
@Composable
fun TaskRow(
    filename: String,
    status: TaskStatus,
    downloaded: Long,
    total: Long,
    speedBps: Double,
    etaSec: Long,
    error: String = "",
    phase: String = "",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(fraction, label = "progress")
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                filename, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            AssistChip(
                onClick = {},
                label = { Text(status.name.lowercase().replace('_', ' ')) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = statusColor(status)
                ),
                modifier = Modifier.height(26.dp)
            )
        }
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(vertical = 2.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                "${Format.bytes(downloaded)} / ${if (total > 0) Format.bytes(total) else "?"}",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)
            )
            if (status == TaskStatus.DOWNLOADING) {
                Text(
                    "${Format.speed(speedBps)}  •  ETA ${Format.eta(etaSec)}" +
                        if (phase == "finalizing") "  •  finalizing…" else "",
                    style = MaterialTheme.typography.labelSmall
                )
            } else if (error.isNotBlank()) {
                Text(
                    error, style = MaterialTheme.typography.labelSmall,
                    color = StatusRed, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(2f)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
