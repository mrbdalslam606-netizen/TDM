package com.tdm.app.core.recovery

import android.content.Context
import com.tdm.app.core.engine.Heartbeat
import com.tdm.app.core.logging.LogRepo
import com.tdm.app.core.model.PauseReason
import com.tdm.app.core.model.TaskStatus
import com.tdm.app.core.model.TaskStateMachine
import com.tdm.app.core.storage.StorageAdapter
import com.tdm.app.data.db.TdmDatabase
import com.tdm.app.telegram.TelegramClientPort
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Recovery Manager (spec §25, §38, §40, §81):
 * "Any component that can die must be rebuildable from persistent state."
 *
 * Startup reconciliation:
 *  1. open DB
 *  2. inspect DownloadTasks in transient states
 *  3. inspect TDLib file state (partial bytes on disk)
 *  4. match DB ↔ TDLib ↔ partial files
 *  5. fix inconsistent states
 *  6. promote recoverable tasks to QUEUED / PAUSED
 *  7. caller restarts the queue engine afterwards
 */
class RecoveryManager(
    private val context: Context,
    private val db: TdmDatabase,
    private val tg: TelegramClientPort,
    private val storage: StorageAdapter,
    private val heartbeat: Heartbeat,
) {

    data class Result(
        val reconciled: Int,
        val resumedToQueue: Int,
        val parkedAsPaused: Int,
        val failedMarked: Int,
        val completedDetected: Int,
        val details: List<String>,
    )

    suspend fun reconcileOnStartup(reason: String): Result {
        val taskDao = db.taskDao()
        val details = mutableListOf<String>()
        LogRepo.log(db, "RECOVERY", "INFO", "startup reconciliation start ($reason)")

        // 1. transient states left by a dead process
        val transient = taskDao.byStatus(TaskStatus.STARTING) +
            taskDao.byStatus(TaskStatus.DOWNLOADING) +
            taskDao.byStatus(TaskStatus.PAUSING)
        for (t in transient) {
            if (TaskStateMachine.validate(t.status, TaskStatus.RECOVERY_PENDING) != null) {
                taskDao.update(
                    t.copy(
                        status = TaskStatus.RECOVERY_PENDING,
                        pauseReason = PauseReason.ENGINE,
                        interruptions = t.interruptions + 1,
                    )
                )
            }
        }
        details += "transient→recovery: ${transient.size}"

        var completedDetected = 0
        var failedMarked = 0

        // 2. RECOVERY_PENDING + previously QUEUED: match with TDLib truth
        val pending = taskDao.byStatus(TaskStatus.RECOVERY_PENDING)
        for (t in pending) {
            val snap = runCatching { tg.fileSnapshot(t.telegramFileId) }.getOrNull()
            when {
                // TDLib already completed the bytes → finalize path will pick it up after requeue
                snap != null && snap.isDownloadingCompleted -> {
                    completedDetected++
                    taskDao.update(t.copy(status = TaskStatus.QUEUED, downloadedBytes = snap.expectedSize, pauseReason = PauseReason.NONE))
                    details += "task ${t.id}: tdlib says complete → requeue for finalize"
                }
                // TDLib partial on disk is the REAL byte truth (spec §72)
                snap != null && snap.downloadedPrefixSize > 0 -> {
                    val bytes = maxOf(t.downloadedBytes, snap.downloadedPrefixSize)
                    taskDao.update(t.copy(status = TaskStatus.QUEUED, downloadedBytes = bytes, pauseReason = PauseReason.NONE))
                    details += "task ${t.id}: resume from ${bytes / (1024 * 1024)} MB (tdlib partial)"
                }
                else -> {
                    // no TDLib state → trust DB bytes (0 unless we had checkpoints)
                    taskDao.update(t.copy(status = TaskStatus.QUEUED, downloadedBytes = t.downloadedBytes, pauseReason = PauseReason.NONE))
                    details += "task ${t.id}: requeue with db bytes=${t.downloadedBytes}"
                }
            }
        }

        // 3. respect manual pauses & retry timers that survived the crash
        val resumedToQueue = pending.size
        var parked = 0

        // 4. stale RETRY_WAIT entries whose retry time passed → queued
        val now = System.currentTimeMillis()
        val retryWait = taskDao.byStatus(TaskStatus.RETRY_WAIT)
        for (t in retryWait) {
            if (t.floodWaitUntil > now) continue
            if (t.nextRetryAt <= now) {
                taskDao.update(t.copy(status = TaskStatus.QUEUED))
                details += "task ${t.id}: retry-wait expired → queued"
            }
        }

        // 5. validate SAF permission — flag, but never destroy the queue (spec §67)
        val profile = db.storageProfileDao().active()
        if (profile != null && !storage.isPermissionValid(profile)) {
            LogRepo.log(db, "STORAGE", "WARN", "SAF permission lost — queue kept, re-select folder required")
            details += "storage permission lost (flagged)"
        }

        // 6. prune old logs
        LogRepo.prune(db.systemLogDao())

        LogRepo.log(db, "RECOVERY", "INFO", "reconciliation done: ${details.joinToString("; ")}")
        return Result(
            reconciled = transient.size + pending.size,
            resumedToQueue = resumedToQueue,
            parkedAsPaused = parked,
            failedMarked = failedMarked,
            completedDetected = completedDetected,
            details = details,
        )
    }

    /**
     * Watchdog-triggered recovery with backoff (spec §33, §38).
     * Returns true when recovery was allowed (not in cooldown / not exceeded).
     */
    fun shouldAttemptRecovery(): Boolean {
        val snap = heartbeat.read() ?: return true
        if (snap.recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return false
        val backoffMs = backoffFor(snap.recoveryAttempts)
        return System.currentTimeMillis() - snap.ts > backoffMs
    }

    fun recordRecoveryAttempt() {
        val snap = heartbeat.read()
        val attempts = (snap?.recoveryAttempts ?: 0) + 1
        heartbeat.write(
            state = "RECOVERY",
            activeDownloads = 0,
            lastProgressBytes = snap?.progressBytes ?: 0,
            tdlibState = snap?.tdlib ?: "UNKNOWN",
            queueCount = snap?.queue ?: 0,
            recoveryAttempts = attempts,
        )
        // attempts reset to 0 on the next healthy engine heartbeat write
    }

    fun isRecoveryFailed(): Boolean {
        val snap = heartbeat.read() ?: return false
        return snap.recoveryAttempts >= MAX_RECOVERY_ATTEMPTS
    }

    /** Detect the engine's live state for the Reliability screen (spec §37). */
    fun engineHealth(): EngineHealth {
        val snap = heartbeat.read()
        val procAlive = isEngineProcessAlive()
        val stale = snap == null || System.currentTimeMillis() - snap.ts > Heartbeat.STALE_MS
        return EngineHealth(
            processAlive = procAlive,
            heartbeat = snap,
            stale = stale,
            healthy = procAlive && !stale,
        )
    }

    data class EngineHealth(
        val processAlive: Boolean,
        val heartbeat: Heartbeat.Snapshot?,
        val stale: Boolean,
        val healthy: Boolean,
    )

    private fun isEngineProcessAlive(): Boolean {
        return runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return true
            am.runningAppProcesses?.any { it.processName == context.packageName } ?: false
        }.getOrDefault(true)
    }

    companion object {
        const val MAX_RECOVERY_ATTEMPTS = 5
        fun backoffFor(attempt: Int): Long = (30_000L shl attempt.coerceIn(0, 5)).coerceAtMost(15 * 60_000L)
    }
}
