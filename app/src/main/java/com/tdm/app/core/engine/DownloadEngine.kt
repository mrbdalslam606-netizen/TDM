package com.tdm.app.core.engine

import android.content.Context
import com.tdm.app.core.concurrency.AutoConcurrencyController
import com.tdm.app.core.concurrency.RateLimiter
import com.tdm.app.core.model.EngineRunState
import com.tdm.app.core.model.PauseReason
import com.tdm.app.core.model.TaskPriority
import com.tdm.app.core.model.TaskStatus
import com.tdm.app.core.model.TaskStateMachine
import com.tdm.app.core.retry.RetryEngine
import com.tdm.app.core.scheduler.ScheduleMatcher
import com.tdm.app.core.storage.PathTemplate
import com.tdm.app.core.storage.StorageAdapter
import com.tdm.app.data.db.DownloadTaskDao
import com.tdm.app.data.db.DownloadTaskEntity
import com.tdm.app.data.db.ScheduleDao
import com.tdm.app.data.db.SourceDao
import com.tdm.app.data.db.TdmDatabase
import com.tdm.app.data.repo.SettingsRepository
import com.tdm.app.core.stats.StatisticsEngine
import com.tdm.app.core.queue.QueueEngine
import com.tdm.app.core.network.NetworkMonitor
import com.tdm.app.telegram.TelegramClientPort
import com.tdm.app.telegram.TgError
import com.tdm.app.telegram.classifyTgError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.io.File

/**
 * Download Manager Core (spec §30, §57, §58).
 * Owns workers; QueueEngine owns ordering; DB owns truth; TDLib owns bytes.
 * A single failed file never stops the queue (spec §14).
 */
class DownloadEngine(
    private val context: Context,
    private val db: TdmDatabase,
    private val settings: SettingsRepository,
    private val tg: TelegramClientPort,
    private val storage: StorageAdapter,
    private val networkMonitor: NetworkMonitor,
    val stateHolder: EngineStateHolder,
    private val heartbeat: Heartbeat,
    private val stats: StatisticsEngine,
    workerScope: CoroutineScope,
) {
    private val taskDao: DownloadTaskDao = db.taskDao()
    private val sourceDao: SourceDao = db.sourceDao()
    private val scheduleDao: ScheduleDao = db.scheduleDao()

    private val scope = CoroutineScope(SupervisorJob() + workerScope.coroutineContext)
    private val queueEngine = QueueEngine()
    private val limiter = RateLimiter(0)
    private val autoController = AutoConcurrencyController(maxConcurrency = 5)

    private val workers = ConcurrentHashMap<Long, WorkerControl>()
    private val loopMutex = Mutex()

    @Volatile private var supervisor: Job? = null
    @Volatile private var sessionAgg: StatisticsEngine.SessionAgg? = null
    @Volatile private var sessionId: Long = 0

    companion object {
        const val CHUNK_BYTES: Int = 512 * 1024
        const val CHUNK_BYTES_L: Long = CHUNK_BYTES.toLong()
        const val CHECKPOINT_BYTES = 8L * 1024 * 1024
        const val CHECKPOINT_MS = 15_000L
    }

    /* ------------------------- worker control ------------------------- */

    private class WorkerControl {
        @Volatile var paused: Boolean = false
        @Volatile var canceled: Boolean = false
        @Volatile var yieldSlot: Boolean = false   // graceful slot release (auto-concurrency shrink)
        @Volatile var downloaded: Long = 0
        @Volatile var lastSpeed: Double = 0.0
        var job: Job? = null
    }

    /* ------------------------- lifecycle ------------------------- */

    fun start() {
        if (supervisor?.isActive == true) return
        supervisor = scope.launch { supervisorLoop() }
    }

    fun stop() {
        supervisor?.cancel()
        supervisor = null
        workers.values.forEach { it.paused = true }
        stateHolder.update { it.copy(runState = EngineRunState.STOPPED) }
    }

    /* ------------------------- control API (UI/service) ------------------------- */

    suspend fun pauseTask(taskId: Long, manual: Boolean) {
        val c = workers[taskId]
        if (c != null) {
            c.paused = true
        } else {
            taskDao.setStatusReason(taskId, TaskStatus.PAUSED, if (manual) PauseReason.MANUAL else PauseReason.SYSTEM_GLOBAL, manual)
        }
    }

    suspend fun resumeTask(taskId: Long) {
        taskDao.setStatusReason(taskId, TaskStatus.QUEUED, PauseReason.NONE, false)
        // re-queued; supervisor picks it up
    }

    suspend fun retryTask(taskId: Long, now: Boolean = false) {
        val t = taskDao.byId(taskId) ?: return
        taskDao.update(
            t.copy(
                status = TaskStatus.QUEUED, pauseReason = PauseReason.NONE,
                nextRetryAt = if (now) 0L else System.currentTimeMillis(), lastError = ""
            )
        )
    }

    suspend fun cancelTask(taskId: Long) {
        val c = workers[taskId]
        if (c != null) c.canceled = true
        val t = taskDao.byId(taskId) ?: return
        val to = TaskStateMachine.validate(t.status, TaskStatus.CANCELED)
        if (to != null) taskDao.setStatus(taskId, TaskStatus.CANCELED)
    }

    suspend fun setPriority(taskId: Long, priority: TaskPriority) {
        val t = taskDao.byId(taskId) ?: return
        taskDao.update(t.copy(priority = priority))
    }

    suspend fun downloadNow(taskId: Long) {
        val t = taskDao.byId(taskId) ?: return
        taskDao.update(t.copy(downloadNowRequested = true, status = TaskStatus.QUEUED, pauseReason = PauseReason.NONE, nextRetryAt = 0))
    }

    suspend fun pauseAll() {
        stateHolder.pauseAllFlag.value = true
        workers.values.forEach { it.paused = true }
        stateHolder.update { it.copy(runState = EngineRunState.PAUSED_ALL) }
        // non-running queued tasks also marked (visible semantics)
        taskDao.activeAndQueued().filter { it.status == TaskStatus.QUEUED }.forEach {
            taskDao.setStatusReason(it.id, TaskStatus.PAUSED, PauseReason.SYSTEM_GLOBAL, it.manualPause)
        }
    }

    suspend fun resumeAll() {
        stateHolder.pauseAllFlag.value = false
        // spec §15: Resume All must NOT lift manual pauses
        val candidates = taskDao.byStatus(TaskStatus.PAUSED).filter { PauseController.resumeAllEligible(it) }
        candidates.forEach { taskDao.setStatusReason(it.id, TaskStatus.QUEUED, PauseReason.NONE, it.manualPause) }
        stateHolder.update { it.copy(runState = EngineRunState.IDLE) }
    }

    fun setSpeedLimitBps(bps: Long) {
        limiter.setLimit(bps)
        stateHolder.update { it.copy(speedLimitBps = bps) }
    }

    /* ------------------------- supervisor loop ------------------------- */

    private suspend fun supervisorLoop() {
        stateHolder.update { it.copy(runState = EngineRunState.IDLE) }
        var heartbeatAt = 0L
        var sampleAt = 0L
        while (scope.isActive) {
            try {
                val now = System.currentTimeMillis()
                val s = settings.current()

                // speed limit: global; profile-level limits applied dynamically below
                var effectiveLimit = s.globalSpeedLimitBps

                // window state across all enabled profiles referenced by sources
                val (windowActive, windowName, windowRemaining, endBehavior) = windowState()
                if (windowActive && endBehavior == "FINISH_CURRENT") Unit

                val pausedAll = stateHolder.pauseAllFlag.value

                // load queue candidates
                val queuedAll = taskDao.activeAndQueued()
                val queued = queuedAll.filter { it.status == TaskStatus.QUEUED }

                // count active workers
                val activeWorkers = workers.size
                val totalSpeed = workers.values.sumOf { it.lastSpeed }
                val overallBytes = workers.values.sumOf { it.downloaded }

                // concurrency target
                val fixedOrAuto = if (s.concurrencyMode == "AUTO") {
                    autoController.maxAllowed().coerceAtMost(s.maxConcurrency)
                    autoController.current.coerceAtMost(s.maxConcurrency)
                } else s.fixedConcurrency
                var target = fixedOrAuto.coerceAtLeast(1)

                // profile concurrency/limit overrides (first matching active profile)
                if (windowActive && windowProfileId != null) {
                    val p = scheduleDao.profileById(windowProfileId!!)
                    if (p != null) {
                        if (p.speedLimitBps > 0) effectiveLimit = if (effectiveLimit > 0) minOf(effectiveLimit, p.speedLimitBps) else p.speedLimitBps
                        if (p.maxConcurrency > 0) target = if (s.concurrencyMode == "AUTO") target.coerceAtMost(p.maxConcurrency) else minOf(target, p.maxConcurrency)
                    }
                }
                limiter.setLimit(effectiveLimit)
                autoController.forceLevel(target.coerceAtMost(s.maxConcurrency))

                val eligible = queued.filter { t ->
                    isEligible(t, windowActive, pausedAll, s.downloadNowOverridesNetwork)
                }

                val slots = target - activeWorkers
                if (slots > 0 && eligible.isNotEmpty() && !pausedAll) {
                    val picked = queueEngine.selectNext(
                        eligible, slots,
                        historicalSpeedBps = avgHistoricalSpeed(),
                        remainingWindowSec = if (windowActive) windowRemaining else Long.MAX_VALUE / 2,
                        activeDownloads = activeWorkers,
                    )
                    for (t in picked) launchWorker(t)
                }

                // graceful shrink for auto-concurrency
                if (workers.size > target) {
                    workers.entries.sortedByDescending { it.key }.take(workers.size - target).forEach { it.value.yieldSlot = true }
                }

                // run state
                val runState = when {
                    pausedAll -> EngineRunState.PAUSED_ALL
                    workers.isNotEmpty() -> if (windowActive) EngineRunState.RUNNING_SCHEDULE else EngineRunState.RUNNING_MANUAL
                    eligible.isNotEmpty() -> EngineRunState.IDLE
                    queued.isNotEmpty() && !windowActive -> EngineRunState.WAITING_SCHEDULE
                    queued.isNotEmpty() -> EngineRunState.WAITING_NETWORK
                    else -> EngineRunState.IDLE
                }

                val nextStart = nextScheduleTime()
                stateHolder.update {
                    it.copy(
                        runState = runState,
                        activeCount = workers.size,
                        queuedCount = queued.size,
                        totalSpeedBps = totalSpeed,
                        overallBytes = overallBytes,
                        speedLimitBps = effectiveLimit,
                        concurrency = workers.size,
                        nextScheduleAt = nextStart,
                        currentScheduleName = windowName,
                    )
                }

                // session accounting
                if (workers.isNotEmpty() && sessionId == 0L) {
                    sessionId = stats.openSession(
                        if (windowActive) "SCHEDULE" else "MANUAL",
                        windowProfileId,
                        netName(),
                    )
                    sessionAgg = StatisticsEngine.SessionAgg()
                } else if (workers.isEmpty() && sessionId != 0L) {
                    stats.closeSession(sessionId, sessionAgg ?: StatisticsEngine.SessionAgg())
                    sessionId = 0L; sessionAgg = null
                }

                // sampling for auto-concurrency + stats (every 5s)
                if (now - sampleAt >= 5000 && workers.isNotEmpty()) {
                    sampleAt = now
                    val speed = workers.values.sumOf { it.lastSpeed }
                    autoController.onSample(speed)
                    autoController.tick()
                    sessionAgg?.sample(speed, workers.size)
                    stats.recordProgress((speed * 5).toLong(), speed, workers.size)
                }

                // heartbeat (spec §33)
                if (now - heartbeatAt >= 5000) {
                    heartbeatAt = now
                    heartbeat.write(
                        state = runState.name,
                        activeDownloads = workers.size,
                        lastProgressBytes = workers.values.maxOfOrNull { it.downloaded } ?: 0,
                        tdlibState = tg.connState.value.name,
                        queueCount = queued.size,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // classified & logged — never swallowed silently (spec §65)
                com.tdm.app.core.logging.LogRepo.log(
                    db, "DOWNLOADS", "ERROR",
                    "supervisor loop error: ${classifyTgError(e).message}"
                )
            }
            delay(2000)
        }
    }

    @Volatile private var windowProfileId: Long? = null

    private data class WindowState(
        val active: Boolean, val name: String?, val remainingSec: Long, val endBehavior: String,
    )

    private suspend fun windowState(): WindowState {
        val profiles = scheduleDao.profiles().filter { it.enabled }
        if (profiles.isEmpty()) return WindowState(false, null, 0, "STOP_IMMEDIATELY")
        for (p in profiles) {
            val windows = scheduleDao.windows(p.id).map {
                ScheduleMatcher.WindowRef(it.daysBitmask, it.startMinuteOfDay, it.endMinuteOfDay)
            }
            if (ScheduleMatcher.isActive(windows)) {
                windowProfileId = p.id
                return WindowState(
                    true, p.name,
                    ScheduleMatcher.remainingSeconds(windows).coerceAtLeast(0),
                    p.endBehavior,
                )
            }
        }
        windowProfileId = null
        return WindowState(false, null, 0, profiles.first().endBehavior)
    }

    private suspend fun nextScheduleTime(): Long? {
        val profiles = scheduleDao.profiles().filter { it.enabled }
        val windows = profiles.flatMap { p ->
            scheduleDao.windows(p.id).map {
                ScheduleMatcher.WindowRef(it.daysBitmask, it.startMinuteOfDay, it.endMinuteOfDay)
            }
        }
        return ScheduleMatcher.nextWindowStart(windows)
    }

    private suspend fun isEligible(
        t: DownloadTaskEntity,
        windowActive: Boolean,
        pausedAll: Boolean,
        dnOverridesNetwork: Boolean,
    ): Boolean {
        if (pausedAll) return false
        if (t.nextRetryAt > System.currentTimeMillis()) return false
        val src = sourceDao.byId(t.sourceId)
        if (src != null && !src.enabled) return false

        val scheduleOk = t.downloadNowRequested || src?.scheduleProfileId == null ||
            (src.scheduleProfileId != null && windowActive)
        if (!scheduleOk) return false

        val policy = src?.networkPolicy ?: com.tdm.app.data.db.NetworkPolicy.ANY
        val networkOk = (t.downloadNowRequested && dnOverridesNetwork) ||
            networkMonitor.satisfied(policy)
        if (!networkOk) return false

        // storage pre-flight (spec §66)
        if (t.size > 0) {
            val profile = db.storageProfileDao().active()
            if (profile != null && !storage.hasSpaceFor(profile, t.size)) return false
        }
        return true
    }

    private suspend fun avgHistoricalSpeed(): Double {
        val rows = db.statisticsDao().since(StatisticsEngine.daysAgo(7))
        val samples = rows.sumOf { it.speedSampleCount }
        return if (samples == 0) 0.0 else rows.sumOf { it.sumSpeedSamplesBps } / samples
    }

    private fun netName(): String = when (networkMonitor.current.value) {
        com.tdm.app.core.network.NetKind.WIFI -> "WIFI"
        com.tdm.app.core.network.NetKind.MOBILE -> "MOBILE"
        com.tdm.app.core.network.NetKind.NONE -> "NONE"
    }

    /* ------------------------- worker ------------------------- */

    private fun launchWorker(task: DownloadTaskEntity) {
        if (workers.containsKey(task.id)) return
        val control = WorkerControl()
        control.downloaded = task.downloadedBytes
        workers[task.id] = control
        control.job = scope.launch {
            try {
                runTask(task.id, control)
            } finally {
                workers.remove(task.id)
            }
        }
    }

    private suspend fun runTask(taskId: Long, control: WorkerControl) {
        var t = taskDao.byId(taskId) ?: return

        val src = sourceDao.byId(t.sourceId)
        val retryPolicy = src?.retryPolicy ?: com.tdm.app.data.db.RetryPolicy()

        // STARTING
        if (TaskStateMachine.validate(t.status, TaskStatus.STARTING) == null) return
        taskDao.setStatus(taskId, TaskStatus.STARTING)
        stateHolder.publishProgress(taskId, EngineStateHolder.TaskProgress(taskId, t.downloadedBytes, t.size, 0.0, eta( t.downloadedBytes, t.size, 0.0), TaskStatus.STARTING, "starting"))

        var snap: com.tdm.app.telegram.TgFileSnapshot =
            resolveFreshFile(t) ?: run {
                failOrRetry(taskId, retryPolicy, t, TgError.FileNotFound().message, "FILE_NOT_FOUND")
                return
            }
        // TDLib may already have a partial on disk → real resume (spec §22, §72)
        var downloaded = maxOf(t.downloadedBytes, snap.downloadedPrefixSize)
        if (downloaded != t.downloadedBytes) {
            t = t.copy(downloadedBytes = downloaded)
            taskDao.updateProgress(taskId, downloaded, System.currentTimeMillis(), t.averageSpeedBps, t.peakSpeedBps)
        }
        if (t.size <= 0 && snap.expectedSize > 0) {
            t = t.copy(size = snap.expectedSize)
        }
        var lastCheckpointBytes = downloaded
        var lastCheckpointAt = System.currentTimeMillis()
        val startedAt = if (t.startedAt > 0) t.startedAt else System.currentTimeMillis()
        if (t.startedAt <= 0) taskDao.update(t.copy(startedAt = startedAt))
        var peak = t.peakSpeedBps
        var speedWindowBytes = 0L
        var speedWindowAt = System.currentTimeMillis()

        taskDao.setStatus(taskId, TaskStatus.DOWNLOADING)

        // main chunk loop
        loop@ while (true) {
            // cooperative gates
            if (control.canceled) {
                taskDao.setStatus(taskId, TaskStatus.CANCELED)
                publish(taskId, downloaded, t.size, 0.0, TaskStatus.CANCELED, "canceled")
                return
            }
            if (control.paused) {
                taskDao.setStatusReason(taskId, TaskStatus.PAUSED, PauseReason.SYSTEM_GLOBAL, t.manualPause)
                publish(taskId, downloaded, t.size, 0.0, TaskStatus.PAUSED, "paused")
                return
            }
            if (control.yieldSlot) {
                // graceful slot release: checkpoint & requeue (NOT a pause)
                taskDao.updateProgress(taskId, downloaded, System.currentTimeMillis(), t.averageSpeedBps, peak)
                taskDao.setStatus(taskId, TaskStatus.QUEUED)
                return
            }

            val expectedTotal = if (t.size > 0) t.size else snap.expectedSize
            if (snap.isDownloadingCompleted || (expectedTotal > 0 && downloaded >= expectedTotal && expectedTotal > 0)) {
                break@loop
            }

            val chunkStart = System.currentTimeMillis()
            try {
                val offset = downloaded
                snap = tg.downloadChunk(snap.fileId, offset, CHUNK_BYTES)
                downloaded = maxOf(downloaded, snap.downloadedPrefixSize)
                control.downloaded = downloaded

                // speed measurement
                val nowMs = System.currentTimeMillis()
                speedWindowBytes += CHUNK_BYTES
                if (nowMs - speedWindowAt >= 1000) {
                    val sp = speedWindowBytes * 1000.0 / (nowMs - speedWindowAt)
                    control.lastSpeed = sp
                    if (sp > peak) peak = sp
                    speedWindowBytes = 0
                    speedWindowAt = nowMs
                    val etaS = eta(downloaded, expectedTotal, sp)
                    publish(taskId, downloaded, expectedTotal, sp, TaskStatus.DOWNLOADING, "downloading", etaS)
                }

                // periodic checkpoint (spec §24)
                if (downloaded - lastCheckpointBytes >= CHECKPOINT_BYTES || nowMs - lastCheckpointAt >= CHECKPOINT_MS) {
                    val dur = activeDuration(t, startedAt)
                    val avgBps = if (dur > 0) downloaded.toDouble() / (dur / 1000.0) else 0.0
                    taskDao.updateProgress(taskId, downloaded, nowMs, avgBps, peak)
                    lastCheckpointBytes = downloaded
                    lastCheckpointAt = nowMs
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                taskDao.updateProgress(taskId, downloaded, System.currentTimeMillis(), t.averageSpeedBps, peak)
                taskDao.setStatusReason(taskId, TaskStatus.PAUSED, PauseReason.ENGINE, t.manualPause)
                throw e
            } catch (e: Throwable) {
                val err = classifyTgError(e)
                when (err) {
                    is TgError.FloodWait -> {
                        val until = System.currentTimeMillis() + RetryEngine.floodWaitDelaySec(err.seconds) * 1000
                        taskDao.update(t.copy(status = TaskStatus.RETRY_WAIT, floodWaitUntil = until, nextRetryAt = until, retryCount = t.retryCount + 1, lastError = err.message, lastErrorClass = "FLOOD_WAIT"))
                        publish(taskId, downloaded, t.size, 0.0, TaskStatus.RETRY_WAIT, "floodwait")
                        return
                    }
                    is TgError.Cancelled -> {
                        taskDao.updateProgress(taskId, downloaded, System.currentTimeMillis(), t.averageSpeedBps, peak)
                        taskDao.setStatusReason(taskId, TaskStatus.PAUSED, PauseReason.SYSTEM_GLOBAL, t.manualPause)
                        return
                    }
                    else -> {
                        // checkpoint state, then retry per policy — queue keeps flowing (spec §14)
                        taskDao.updateProgress(taskId, downloaded, System.currentTimeMillis(), t.averageSpeedBps, peak)
                        failOrRetry(taskId, retryPolicy, t.copy(downloadedBytes = downloaded, peakSpeedBps = peak), err.message, err.javaClass.simpleName)
                        return
                    }
                }
            }

            // rate limiting between chunks (spec §11)
            limiter.acquire(CHUNK_BYTES)
        }

        // ---- finalize (spec §23) ----
        publish(taskId, downloaded, t.size, 0.0, TaskStatus.DOWNLOADING, "finalizing")
        val profile = db.storageProfileDao().active()
        if (profile == null) {
            taskDao.update(t.copy(status = TaskStatus.PAUSED, pauseReason = PauseReason.STORAGE, lastError = "Storage folder not selected"))
            stateHolder.update { it.copy(lastError = "Storage folder not selected") }
            return
        }
        val local = File(snap.localPath)
        if (!local.exists()) {
            failOrRetry(taskId, retryPolicy, t.copy(downloadedBytes = downloaded), "TDLib final file missing", "FINALIZE")
            return
        }
        val segments = PathTemplate.resolve(
            profile.pathTemplate,
            channel = src?.name ?: "Unknown",
            filename = PathTemplate.namingResolve(src?.namingTemplate ?: "{original_name}", t.filename, src?.name ?: "Unknown"),
        )
        val result = storage.finalizeFile(profile, segments, local, t.size) { copied ->
            stateHolder.publishProgress(
                taskId,
                EngineStateHolder.TaskProgress(taskId, copied, t.size, 0.0, 0, TaskStatus.DOWNLOADING, "finalizing")
            )
        }
        when (result) {
            is StorageAdapter.StorageOp.Success -> {
                val dur = activeDuration(t, startedAt)
                val avgBps = if (dur > 0) t.size.toDouble() / (dur / 1000.0) else 0.0
                taskDao.update(
                    t.copy(
                        status = TaskStatus.COMPLETED,
                        downloadedBytes = t.size,
                        destinationUri = result.uri.toString(),
                        destinationPathHint = result.finalPathHint,
                        completedAt = System.currentTimeMillis(),
                        startedAt = startedAt,
                        averageSpeedBps = avgBps,
                        peakSpeedBps = peak,
                        activeDurationMs = dur,
                        lastError = "",
                    )
                )
                publish(taskId, t.size, t.size, 0.0, TaskStatus.COMPLETED, "completed")
                stats.recordFileCompleted(t.size)
                sessionAgg?.let { it.completed++; it.bytes += t.size }
            }
            is StorageAdapter.StorageOp.Failure -> {
                when (result.kind) {
                    StorageAdapter.StorageOp.Kind.PERMISSION_LOST -> {
                        taskDao.update(t.copy(status = TaskStatus.PAUSED, pauseReason = PauseReason.STORAGE, lastError = result.message))
                        stateHolder.update { it.copy(lastError = result.message) }
                    }
                    StorageAdapter.StorageOp.Kind.INSUFFICIENT_STORAGE -> {
                        taskDao.update(t.copy(status = TaskStatus.INSUFFICIENT_STORAGE, lastError = result.message))
                        stateHolder.update { it.copy(lastError = result.message) }
                    }
                    else -> failOrRetry(taskId, retryPolicy, t.copy(downloadedBytes = downloaded), result.message, "FINALIZE")
                }
                publish(taskId, downloaded, t.size, 0.0, TaskStatus.FAILED, "finalize-error")
            }
        }
    }

    /** Refresh stale TDLib file id by re-fetching the message (spec §57). */
    private suspend fun resolveFreshFile(t: DownloadTaskEntity): com.tdm.app.telegram.TgFileSnapshot? {
        val direct = runCatching { tg.fileSnapshot(t.telegramFileId) }.getOrNull()
        if (direct != null) return direct
        val msg = tg.message(t.telegramChatId, t.telegramMessageId) ?: return null
        val f = msg.file ?: return null
        if (f.fileId != t.telegramFileId || f.fileUniqueId != t.telegramFileUniqueId) {
            db.taskDao().update(t.copy(telegramFileId = f.fileId, telegramFileUniqueId = f.fileUniqueId, size = f.expectedSize))
        }
        return tg.fileSnapshot(f.fileId)
    }

    private suspend fun failOrRetry(
        taskId: Long,
        policy: com.tdm.app.data.db.RetryPolicy,
        t: DownloadTaskEntity,
        error: String,
        errorClass: String,
    ) {
        val decision = RetryEngine.decide(policy, t.retryCount)
        stats.recordRetries(1)
        if (decision.shouldRetry) {
            val nextAt = System.currentTimeMillis() + decision.delaySec * 1000
            db.taskDao().update(
                t.copy(
                    status = TaskStatus.RETRY_WAIT,
                    retryCount = decision.attempt,
                    nextRetryAt = nextAt,
                    lastError = error,
                    lastErrorClass = errorClass,
                )
            )
            publish(taskId, t.downloadedBytes, t.size, 0.0, TaskStatus.RETRY_WAIT, "retry-wait")
        } else {
            db.taskDao().update(
                t.copy(
                    status = TaskStatus.FAILED,
                    retryCount = t.retryCount + 1,
                    lastError = error,
                    lastErrorClass = errorClass,
                    nextRetryAt = 0,
                )
            )
            stats.recordFileFailed()
            sessionAgg?.failed++
            publish(taskId, t.downloadedBytes, t.size, 0.0, TaskStatus.FAILED, "failed")
        }
    }

    private fun publish(
        taskId: Long,
        bytes: Long,
        total: Long,
        speed: Double,
        status: TaskStatus,
        phase: String,
        etaSec: Long = eta(bytes, total, speed),
    ) {
        stateHolder.publishProgress(taskId, EngineStateHolder.TaskProgress(taskId, bytes, total, speed, etaSec, status, phase))
    }

    private fun eta(bytes: Long, total: Long, speed: Double): Long {
        if (speed <= 0 || total <= 0) return -1
        return ((total - bytes).coerceAtLeast(0) / speed).toLong()
    }

    private fun activeDuration(t: DownloadTaskEntity, startedAt: Long): Long {
        val now = if (t.completedAt > 0) t.completedAt else System.currentTimeMillis()
        return (now - startedAt).coerceAtLeast(0) - t.pauseDurationMs
    }
}
