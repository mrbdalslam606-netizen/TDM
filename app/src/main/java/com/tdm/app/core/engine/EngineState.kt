package com.tdm.app.core.engine

import com.tdm.app.core.model.EngineRunState
import com.tdm.app.core.model.PauseReason
import com.tdm.app.data.db.DownloadTaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single shared engine state — consumed by UI + notification. RAM copy; DB is the truth. */
data class EngineSnapshot(
    val runState: EngineRunState = EngineRunState.STOPPED,
    val activeCount: Int = 0,
    val queuedCount: Int = 0,
    val totalSpeedBps: Double = 0.0,
    val overallBytes: Long = 0,
    val overallTotal: Long = 0,
    val currentFile: String = "",
    val sessionBytes: Long = 0,
    val nextScheduleAt: Long? = null,
    val currentScheduleName: String? = null,
    val network: String = "",
    val concurrency: Int = 0,
    val speedLimitBps: Long = 0,
    val recoveryAttempts: Int = 0,
    val lastError: String = "",
)

class EngineStateHolder {
    private val _snapshot = MutableStateFlow(EngineSnapshot())
    val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    /** Live progress of every task the UI shows (id → bytes) */
    private val _liveProgress = MutableStateFlow<Map<Long, TaskProgress>>(emptyMap())
    val liveProgress: StateFlow<Map<Long, TaskProgress>> = _liveProgress.asStateFlow()

    val pauseAllFlag = MutableStateFlow(false)

    fun update(transform: (EngineSnapshot) -> EngineSnapshot) {
        _snapshot.value = transform(_snapshot.value)
    }

    fun publishProgress(id: Long, p: TaskProgress?) {
        val cur = _liveProgress.value.toMutableMap()
        if (p == null) cur.remove(id) else cur[id] = p
        _liveProgress.value = cur
    }

    data class TaskProgress(
        val taskId: Long,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Double,
        val etaSec: Long,
        val status: com.tdm.app.core.model.TaskStatus,
        val phase: String, // "downloading" | "finalizing"
    )
}

/**
 * Pause semantics (spec §15):
 *  - MANUAL pause on a specific task: only that task, only user can lift it.
 *  - Resume All lifts SYSTEM_GLOBAL / SCHEDULE / NETWORK / ENGINE, never MANUAL.
 */
object PauseController {

    fun isManuallyPaused(task: DownloadTaskEntity): Boolean =
        task.manualPause && task.pauseReason == PauseReason.MANUAL

    fun resumeAllEligible(task: DownloadTaskEntity): Boolean =
        task.status == com.tdm.app.core.model.TaskStatus.PAUSED &&
            task.pauseReason in setOf(
                PauseReason.SYSTEM_GLOBAL, PauseReason.SCHEDULE,
                PauseReason.NETWORK, PauseReason.ENGINE, PauseReason.STORAGE,
            )
}
