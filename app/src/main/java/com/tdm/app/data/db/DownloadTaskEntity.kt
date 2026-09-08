package com.tdm.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tdm.app.core.model.PauseReason
import com.tdm.app.core.model.TaskPriority
import com.tdm.app.core.model.TaskStatus

/**
 * DownloadTask — the single source of truth for the queue (spec §29, §57).
 * All fields from the spec are present; extra operational columns are marked.
 */
@Entity(
    tableName = "download_tasks",
    indices = [
        Index("status"), Index("sourceId"), Index("orderingKey"),
        Index(value = ["telegramChatId", "telegramMessageId"], unique = true),
        Index(value = ["telegramFileUniqueId", "size"], unique = false),
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // --- identity ---
    val sourceId: Long = 0,
    val telegramChatId: Long = 0,
    val telegramMessageId: Long = 0,
    val telegramFileId: Int = 0,           // mutable TDLib file id (may change between sessions)
    val telegramFileUniqueId: String = "", // stable identity across sessions
    val fileRemoteId: String = "",         // remote.unique_id fallback when available
    val size: Long = 0,
    val filename: String = "",
    val mimeHint: String = "",

    // --- queue state ---
    val status: TaskStatus = TaskStatus.DISCOVERED,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val orderingKey: Long = 0,             // filled by QueueEngine per QueueMode
    val manualOrderKey: Long = 0,          // explicit manual ordering
    val pauseReason: PauseReason = PauseReason.NONE,
    val manualPause: Boolean = false,      // user paused THIS task specifically (spec §15)

    // --- progress / resume (spec §22-24) ---
    val downloadedBytes: Long = 0,         // checkpointed; reconciled with TDLib on startup
    val partialFileReference: String = "", // TDLib partial path / local marker
    val destinationUri: String = "",       // resolved SAF document uri after finalize
    val destinationPathHint: String = "",  // human-readable resolved path for UI
    val finalizedBytes: Long = 0,          // bytes copied into SAF .partial during finalize

    // --- lifecycle timestamps ---
    val createdAt: Long = 0,
    val startedAt: Long = 0,
    val completedAt: Long = 0,
    val lastProgressAt: Long = 0,
    val lastCheckpointAt: Long = 0,

    // --- retry (spec §41) ---
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0,
    val lastError: String = "",
    val lastErrorClass: String = "",       // error classification id
    val floodWaitUntil: Long = 0,          // FLOOD_WAIT_X respected strictly

    // --- config snapshot (independent of later template edits) ---
    val scheduleProfileId: Long? = null,
    val networkPolicyOverride: NetworkPolicy? = null,
    val sourceQueueMode: QueueMode = QueueMode.FIFO,
    val sequentialLock: Boolean = false,   // natural/telegram ordering: do not reorder past earlier pending sibling
    val enqueueSessionId: Long? = null,
    val downloadNowRequested: Boolean = false, // bypasses schedule gating (spec §9)

    // --- statistics (spec §20) ---
    val averageSpeedBps: Double = 0.0,
    val peakSpeedBps: Double = 0.0,
    val activeDurationMs: Long = 0,        // time actually transferring
    val pauseDurationMs: Long = 0,
    val interruptions: Int = 0,
    val networkType: String = "",          // network used for last activity
    val concurrencyUsed: Int = 0,
)
