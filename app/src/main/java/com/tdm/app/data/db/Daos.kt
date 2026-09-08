package com.tdm.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tdm.app.core.model.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun byId(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks WHERE telegramChatId = :chatId AND telegramMessageId = :messageId LIMIT 1")
    suspend fun byMessage(chatId: Long, messageId: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE telegramFileUniqueId = :uniqueId AND size = :size LIMIT 1")
    suspend fun byFileIdentity(uniqueId: String, size: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE telegramChatId = :chatId AND telegramMessageId = :messageId LIMIT 1")
    fun observeByMessage(chatId: Long, messageId: Long): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks WHERE status IN ('QUEUED','STARTING','DOWNLOADING','PAUSING','RETRY_WAIT','RECOVERY_PENDING','INSUFFICIENT_STORAGE') ORDER BY priority DESC, orderingKey ASC")
    suspend fun activeAndQueued(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status IN ('QUEUED','STARTING','DOWNLOADING','PAUSING','RETRY_WAIT','RECOVERY_PENDING','INSUFFICIENT_STORAGE') ORDER BY priority DESC, orderingKey ASC")
    fun observeQueue(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = :status")
    suspend fun byStatus(status: TaskStatus): List<DownloadTaskEntity>

    @Query("UPDATE download_tasks SET status = :to, lastError = :error WHERE status = :from")
    suspend fun bulkTransition(from: TaskStatus, to: TaskStatus, error: String = ""): Int

    @Query("UPDATE download_tasks SET status = 'RECOVERY_PENDING', pauseReason = 'ENGINE' WHERE status IN ('STARTING','DOWNLOADING','PAUSING')")
    suspend fun markActiveAsRecoveryPending(): Int

    @Query("UPDATE download_tasks SET status = 'QUEUED', pauseReason = 'NONE' WHERE status = 'RECOVERY_PENDING' AND manualPause = 0")
    suspend fun promoteRecoverableToQueued(): Int

    @Query("UPDATE download_tasks SET status = 'PAUSED', pauseReason = 'ENGINE' WHERE status = 'RECOVERY_PENDING' AND (manualPause = 1 OR downloadNowRequested = 0 AND :respectSchedule = 1)")
    suspend fun parkRecoverableManuallyPaused(respectSchedule: Boolean): Int

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status = :status")
    suspend fun countByStatus(status: TaskStatus): Int

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE completedAt BETWEEN :from AND :to AND status = 'COMPLETED'")
    suspend fun completedBetween(from: Long, to: Long): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status IN ('FAILED','RETRY_WAIT') ORDER BY nextRetryAt ASC")
    fun observeFailed(): Flow<List<DownloadTaskEntity>>

    @Query("UPDATE download_tasks SET downloadedBytes = :bytes, lastProgressAt = :at, averageSpeedBps = :avgSpeed, peakSpeedBps = :peakSpeed WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, at: Long, avgSpeed: Double, peakSpeed: Double)

    @Query("UPDATE download_tasks SET status = :status, pauseReason = :reason, manualPause = :manualPause WHERE id = :id")
    suspend fun setStatusReason(id: Long, status: TaskStatus, reason: com.tdm.app.core.model.PauseReason, manualPause: Boolean)

    @Query("UPDATE download_tasks SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: TaskStatus)
}

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity): Long

    @Query("SELECT * FROM sources ORDER BY name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1 AND monitoringEnabled = 1")
    suspend fun monitored(): List<SourceEntity>

    @Query("SELECT * FROM sources")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE chatId = :chatId LIMIT 1")
    suspend fun byIdChat(chatId: Long): SourceEntity?

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sources SET lastProcessedMessageId = :messageId, lastScanAt = :at WHERE id = :id")
    suspend fun updateBookmark(id: Long, messageId: Long, at: Long)

    @Query("UPDATE sources SET seeded = 1 WHERE id = :id")
    suspend fun markSeeded(id: Long)
}

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM telegram_accounts ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM telegram_accounts ORDER BY lastUsedAt DESC")
    suspend fun observeAllOnce(): List<AccountEntity>

    @Query("SELECT * FROM telegram_accounts WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Query("UPDATE telegram_accounts SET lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE telegram_accounts SET loggedIn = :loggedIn WHERE id = :id")
    suspend fun setLoggedIn(id: String, loggedIn: Boolean)

    @Query("DELETE FROM telegram_accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SourceTemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: SourceTemplateEntity): Long

    @Query("SELECT * FROM source_templates ORDER BY name")
    fun observeAll(): Flow<List<SourceTemplateEntity>>

    @Query("SELECT * FROM source_templates WHERE id = :id")
    suspend fun byId(id: Long): SourceTemplateEntity?

    @Query("DELETE FROM source_templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(p: ScheduleProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWindow(w: ScheduleWindowEntity): Long

    @Query("SELECT * FROM schedule_profiles ORDER BY name")
    fun observeProfiles(): Flow<List<ScheduleProfileEntity>>

    @Query("SELECT * FROM schedule_profiles")
    suspend fun profiles(): List<ScheduleProfileEntity>

    @Query("SELECT * FROM schedule_profiles WHERE id = :id")
    suspend fun profileById(id: Long): ScheduleProfileEntity?

    @Query("SELECT * FROM schedule_windows WHERE profileId = :profileId")
    suspend fun windows(profileId: Long): List<ScheduleWindowEntity>

    @Query("SELECT * FROM schedule_windows WHERE profileId = :profileId")
    fun observeWindows(profileId: Long): Flow<List<ScheduleWindowEntity>>

    @Query("DELETE FROM schedule_windows WHERE id = :id")
    suspend fun deleteWindow(id: Long)

    @Query("DELETE FROM schedule_profiles WHERE id = :id")
    suspend fun deleteProfile(id: Long)

    @Query("DELETE FROM schedule_windows WHERE profileId = :profileId")
    suspend fun deleteWindowsOf(profileId: Long)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: DownloadSessionEntity): Long

    @Update
    suspend fun update(s: DownloadSessionEntity)

    @Query("SELECT * FROM download_sessions WHERE id = :id")
    suspend fun byId(id: Long): DownloadSessionEntity?

    @Query("SELECT * FROM download_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<DownloadSessionEntity>>

    @Query("SELECT * FROM download_sessions WHERE endedAt = 0 LIMIT 1")
    suspend fun openSession(): DownloadSessionEntity?

    @Query("UPDATE download_sessions SET endedAt = :at WHERE id = :id AND endedAt = 0")
    suspend fun closeAll(id: Long, at: Long)
}

@Dao
interface StatisticsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: DownloadStatisticsEntity)

    @Query("SELECT * FROM download_statistics WHERE dayEpoch = :day LIMIT 1")
    suspend fun byDay(day: Long): DownloadStatisticsEntity?

    @Query("SELECT * FROM download_statistics WHERE dayEpoch >= :fromDay")
    suspend fun since(fromDay: Long): List<DownloadStatisticsEntity>
}

@Dao
interface StorageProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: StorageProfileEntity): Long

    @Query("SELECT * FROM storage_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun active(): StorageProfileEntity?

    @Query("SELECT * FROM storage_profiles WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<StorageProfileEntity?>

    @Query("UPDATE storage_profiles SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE storage_profiles SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)
}

@Dao
interface SystemLogDao {
    @Insert
    suspend fun insert(l: SystemLogEntity)

    @Insert
    suspend fun insertAll(l: List<SystemLogEntity>)

    @Query("SELECT * FROM system_log ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<SystemLogEntity>>

    @Query("DELETE FROM system_log WHERE at < :before")
    suspend fun prune(before: Long)
}
