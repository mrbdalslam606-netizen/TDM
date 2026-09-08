package com.tdm.app.core.queue

import com.tdm.app.core.model.TaskPriority
import com.tdm.app.data.db.DownloadTaskEntity
import com.tdm.app.data.db.QueueMode
import kotlin.math.ln
import kotlin.math.max

/**
 * Queue ordering (spec §13) + SmartQueue scoring (spec §17).
 * Pure functions — fully unit-testable (spec §71).
 */
object QueueOrdering {

    fun orderingKey(
        task: DownloadTaskEntity,
        mode: QueueMode,
        historicalSpeedBps: Double,
        remainingWindowSec: Long,
        activeDownloads: Int,
    ): Long = when (mode) {
        QueueMode.FIFO -> task.id
        QueueMode.TELEGRAM_MESSAGE -> task.telegramMessageId
        QueueMode.FILENAME_NATURAL -> naturalRank(task) // rank resolved separately; id fallback
        QueueMode.DATE_TIME -> task.createdAt
        QueueMode.FILE_SIZE_ASC -> task.size
        QueueMode.FILE_SIZE_DESC -> -task.size
        QueueMode.PRIORITY -> 0L // priority column handles it; stable by id
        QueueMode.MANUAL -> task.manualOrderKey
        QueueMode.SMART -> task.id // actual ordering via [compare]/[smartScore]
    }

    /**
     * Natural ordering needs string comparison, not a numeric key.
     * The engine calls this to assign sequential ranks to QUEUED tasks of one source.
     */
    fun naturalRank(task: DownloadTaskEntity): Long = task.id // placeholder key; see [naturalRankOf]

    fun <T : Any> naturalRankOf(name: String, indexOf: (String) -> Long): Long = indexOf(name)

    /** Compare two tasks by mode; returns negative → a first. */
    fun compare(
        a: DownloadTaskEntity,
        b: DownloadTaskEntity,
        mode: QueueMode,
        speedBps: Double,
        remainingWindowSec: Long,
        activeDownloads: Int,
    ): Int {
        // priority is always the first dimension (spec §16)
        val pc = b.priority.weight.compareTo(a.priority.weight)
        if (pc != 0) return pc
        return when (mode) {
            QueueMode.FIFO -> a.id.compareTo(b.id)
            QueueMode.TELEGRAM_MESSAGE -> a.telegramMessageId.compareTo(b.telegramMessageId)
            QueueMode.FILENAME_NATURAL -> NaturalOrder.compare(a.filename, b.filename)
            QueueMode.DATE_TIME -> a.createdAt.compareTo(b.createdAt)
            QueueMode.FILE_SIZE_ASC -> a.size.compareTo(b.size)
            QueueMode.FILE_SIZE_DESC -> b.size.compareTo(a.size)
            QueueMode.PRIORITY -> a.id.compareTo(b.id)
            QueueMode.MANUAL -> a.manualOrderKey.compareTo(b.manualOrderKey)
            QueueMode.SMART -> smartScore(b, speedBps, remainingWindowSec, activeDownloads)
                .compareTo(smartScore(a, speedBps, remainingWindowSec, activeDownloads))
        }
    }

    /**
     * SmartQueue score (spec §17) — higher is better. Deterministic, not random.
     * Factors: priority, expected fit into remaining window, historical throughput,
     * retry exhaustion risk, size (slight preference to small files when window is short).
     */
    fun smartScore(
        task: DownloadTaskEntity,
        historicalSpeedBps: Double,
        remainingWindowSec: Long,
        activeDownloads: Int,
    ): Double {
        var score = task.priority.weight * 10.0

        val speed = if (historicalSpeedBps > 1.0) historicalSpeedBps else 2.0 * 1024 * 1024
        val remaining = max(remainingWindowSec, 0)

        // How much of this file fits in the remaining window (0..∞)
        val fitRatio = (remaining * speed) / max(task.size - task.downloadedBytes, 1)
        score += when {
            fitRatio >= 1.5 -> 40.0
            fitRatio >= 1.0 -> 25.0
            fitRatio >= 0.5 -> 10.0
            else -> -30.0 // will very likely NOT complete — deprioritize within same priority
        }

        // Failed repeatedly → lower score so it does not starve the queue (spec §14)
        score -= task.retryCount * 5.0

        // Remaining bytes: prefer finishing files close to done (wasted partials are costly)
        val remainingMB = max(task.size - task.downloadedBytes, 0) / (1024.0 * 1024.0)
        score -= ln(max(remainingMB, 1.0)) * 2.0

        // Manual "Download Now" always jumps the schedule-aware ordering
        if (task.downloadNowRequested) score += 1000.0

        // Slight penalty when many downloads active: prefer files that close slots quickly
        if (activeDownloads >= 3) score += if (remainingMB < 200) 5.0 else -2.0

        return score
    }

    fun duplicateIdentity(chatId: Long, messageId: Long, uniqueId: String, size: Long): String =
        "c$chatId:m$messageId:u$uniqueId:s$size"
}
