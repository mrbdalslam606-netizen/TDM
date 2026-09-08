package com.tdm.app.core.queue

import com.tdm.app.core.model.TaskStatus
import com.tdm.app.data.db.DownloadTaskEntity
import com.tdm.app.data.db.QueueMode

/**
 * Queue Engine — the ONLY component that decides which task starts next, when, and why (spec §58).
 * Workers never decide ordering (spec §58).
 */
class QueueEngine {

    /**
     * Pick the next [count] tasks to run from [candidates] (already status=QUEUED).
     * Respects: priority → queue mode → sequential locks (natural/telegram order) → retry-wait times.
     */
    fun selectNext(
        candidates: List<DownloadTaskEntity>,
        count: Int,
        historicalSpeedBps: Double,
        remainingWindowSec: Long,
        activeDownloads: Int,
        defaultMode: QueueMode = QueueMode.FIFO,
    ): List<DownloadTaskEntity> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val eligible = candidates.filter {
            it.status == TaskStatus.QUEUED && it.nextRetryAt <= System.currentTimeMillis()
        }
        if (eligible.isEmpty()) return emptyList()

        // group by source — modes are per-source
        val bySource = eligible.groupBy { it.sourceId }
        val picked = mutableListOf<DownloadTaskEntity>()

        for ((_, tasks) in bySource) {
            val mode = tasks.firstOrNull()?.sourceQueueMode ?: defaultMode
            val sorted = tasks.sortedWith { a, b ->
                QueueOrdering.compare(a, b, mode, historicalSpeedBps, remainingWindowSec, activeDownloads)
            }
            val lock = sorted.firstOrNull()?.sequentialLock == true
            if (lock && mode in setOf(QueueMode.FILENAME_NATURAL, QueueMode.TELEGRAM_MESSAGE)) {
                // sequential lock: only the FIRST pending task of this source may start (spec §17)
                if (picked.size < count) picked.add(sorted.first())
            } else {
                for (t in sorted) {
                    if (picked.size >= count) break
                    picked.add(t)
                }
            }
            if (picked.size >= count) break
        }
        return picked.take(count)
    }

    /** Failed files never block the queue (spec §14): a failed task is simply excluded. */
    fun excludeFailed(tasks: List<DownloadTaskEntity>): List<DownloadTaskEntity> =
        tasks.filter { it.status != TaskStatus.FAILED && it.status != TaskStatus.CANCELED }
}
