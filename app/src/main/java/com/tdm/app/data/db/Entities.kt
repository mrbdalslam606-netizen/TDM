package com.tdm.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A monitored channel/group/chat or Download Inbox (spec §4). */
@Entity(tableName = "sources", indices = [Index(value = ["chatId"], unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String = "legacy",
    val name: String,
    val type: SourceType = SourceType.CHANNEL,
    val chatId: Long,
    val messageId: Long = 0,
    val topicId: Long? = null,
    val enabled: Boolean = true,
    val monitoringEnabled: Boolean = true,
    val autoDownload: Boolean = true,
    val filter: FileFilter = FileFilter(),
    val scheduleProfileId: Long? = null,
    val queueMode: QueueMode = QueueMode.FIFO,
    val smartQueueEnabled: Boolean = false,
    val priority: com.tdm.app.core.model.TaskPriority = com.tdm.app.core.model.TaskPriority.NORMAL,
    val storageTemplate: String = "{root}/{channel}/{date}/{filename}",
    val namingTemplate: String = "{original_name}",
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val concurrencyPreference: Int = 0,          // 0 = follow global
    val appliedTemplateId: Long? = null,         // provenance only — source is independent after apply (spec §5)

    // monitoring bookkeeping (spec §63)
    val lastProcessedMessageId: Long = 0,
    val startFromMode: StartFromMode = StartFromMode.NOW,
    val startFromValue: String = "",             // N / ISO date / "minId-maxId"
    val seeded: Boolean = false,                 // initial backlog scan done
    val lastScanAt: Long = 0,
)

/** Source Template — base configuration copied on apply; later edits don't propagate (spec §5-6). */
@Entity(tableName = "source_templates")
data class SourceTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val monitoringEnabled: Boolean = true,
    val autoDownload: Boolean = true,
    val filter: FileFilter = FileFilter(),
    val queueMode: QueueMode = QueueMode.FIFO,
    val smartQueueEnabled: Boolean = false,
    val priority: com.tdm.app.core.model.TaskPriority = com.tdm.app.core.model.TaskPriority.NORMAL,
    val storageTemplate: String = "{root}/{channel}/{date}/{filename}",
    val namingTemplate: String = "{original_name}",
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val scheduleProfileId: Long? = null,
    val createdAt: Long = 0,
)

/** Schedule Profile — reusable set of time windows (spec §7). */
@Entity(tableName = "schedule_profiles")
data class ScheduleProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val maxConcurrency: Int = 0,                 // 0 = follow global / auto
    val autoConcurrency: Boolean = true,
    val speedLimitBps: Long = 0,                 // 0 = unlimited
    val endBehavior: String = "STOP_IMMEDIATELY",// STOP_IMMEDIATELY | FINISH_CURRENT
    val createdAt: Long = 0,
)

/** One time window of a profile. Local phone time; handles overnight windows (end < start). */
@Entity(tableName = "schedule_windows", indices = [Index("profileId")])
data class ScheduleWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val daysBitmask: Int,      // bit0=Monday .. bit6=Sunday
    val startMinuteOfDay: Int, // 0..1439
    val endMinuteOfDay: Int,   // may be < startMinuteOfDay → crosses midnight
)

/** A download session — one effective run (schedule window or manual) (spec §59). */
@Entity(tableName = "download_sessions")
data class DownloadSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long = 0,
    val trigger: String = "SCHEDULE",            // SCHEDULE | MANUAL | RECOVERY
    val scheduleProfileId: Long? = null,
    val network: String = "",
    val filesStarted: Int = 0,
    val filesCompleted: Int = 0,
    val filesFailed: Int = 0,
    val bytes: Long = 0,
    val avgSpeedBps: Double = 0.0,
    val peakSpeedBps: Double = 0.0,
    val avgConcurrency: Double = 0.0,
)

/** Daily aggregate for the Statistics screen (spec §44). */
@Entity(tableName = "download_statistics", indices = [Index(value = ["dayEpoch"], unique = true)])
data class DownloadStatisticsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayEpoch: Long,                 // local day start, epoch millis
    val bytesCompleted: Long = 0,
    val filesCompleted: Int = 0,
    val filesFailed: Int = 0,
    val retryCount: Int = 0,
    val activeDownloadMs: Long = 0,
    val peakSpeedBps: Double = 0.0,
    val sumSpeedSamplesBps: Double = 0.0, // for weighted avg speed
    val speedSampleCount: Int = 0,
)

/** Storage profile — persisted SAF tree + template (spec §27). */
@Entity(tableName = "storage_profiles")
data class StorageProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "Default",
    val treeUri: String,
    val pathTemplate: String = "{root}/{channel}/{date}/{filename}",
    val isActive: Boolean = true,
)

/** Reliability / recovery event log (spec §33, §38: recovery reason logging). */
@Entity(tableName = "system_log", indices = [Index("at")])
data class SystemLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val category: String,   // TELEGRAM, DOWNLOADS, QUEUE, SCHEDULER, STORAGE, NETWORK, RECOVERY, WATCHDOG, SHIZUKU
    val severity: String,   // INFO, WARN, ERROR
    val message: String,
)
