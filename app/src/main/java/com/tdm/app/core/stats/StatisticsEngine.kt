package com.tdm.app.core.stats

import com.tdm.app.data.db.DownloadSessionEntity
import com.tdm.app.data.db.DownloadStatisticsEntity
import com.tdm.app.data.db.SessionDao
import com.tdm.app.data.db.StatisticsDao
import java.util.Calendar

/**
 * Statistics Engine (spec §20, §44) + Session Manager (spec §59).
 * Simple reliable statistics — no ML (spec §20), used by prediction & auto-concurrency.
 */
class StatisticsEngine(
    private val statsDao: StatisticsDao,
    private val sessionDao: SessionDao,
) {

    /* ------------------------- sessions ------------------------- */

    suspend fun openSession(trigger: String, profileId: Long?, network: String): Long {
        return sessionDao.insert(
            DownloadSessionEntity(
                startedAt = System.currentTimeMillis(),
                trigger = trigger,
                scheduleProfileId = profileId,
                network = network,
            )
        )
    }

    suspend fun closeSession(sessionId: Long, agg: SessionAgg) {
        val s = sessionDao.byId(sessionId) ?: return
        sessionDao.update(
            s.copy(
                endedAt = System.currentTimeMillis(),
                filesStarted = agg.started,
                filesCompleted = agg.completed,
                filesFailed = agg.failed,
                bytes = agg.bytes,
                avgSpeedBps = agg.avgSpeed,
                peakSpeedBps = agg.peakSpeed,
                avgConcurrency = agg.avgConcurrency,
            )
        )
    }

    class SessionAgg {
        var started = 0; var completed = 0; var failed = 0
        var bytes = 0L; var peakSpeed = 0.0
        var speedSum = 0.0; var speedSamples = 0
        var concurrencySum = 0.0; var concurrencySamples = 0
        val avgSpeed: Double get() = if (speedSamples == 0) 0.0 else speedSum / speedSamples
        val avgConcurrency: Double get() = if (concurrencySamples == 0) 0.0 else concurrencySum / concurrencySamples
        fun sample(speedBps: Double, concurrency: Int) {
            speedSum += speedBps; speedSamples++
            if (speedBps > peakSpeed) peakSpeed = speedBps
            concurrencySum += concurrency; concurrencySamples++
        }
    }

    /* ------------------------- daily aggregates ------------------------- */

    private suspend fun dayEntity(dayStart: Long): DownloadStatisticsEntity =
        statsDao.byDay(dayStart) ?: DownloadStatisticsEntity(dayEpoch = dayStart)

    suspend fun recordProgress(bytes: Long, speedBps: Double, concurrency: Int) {
        if (bytes <= 0) return
        val day = localDayStart()
        val e = dayEntity(day)
        statsDao.upsert(
            e.copy(
                bytesCompleted = e.bytesCompleted + bytes,
                activeDownloadMs = e.activeDownloadMs + 1000,
                peakSpeedBps = maxOf(e.peakSpeedBps, speedBps),
                sumSpeedSamplesBps = e.sumSpeedSamplesBps + speedBps,
                speedSampleCount = e.speedSampleCount + 1,
            )
        )
    }

    suspend fun recordFileCompleted(sizeBytes: Long) {
        val day = localDayStart()
        val e = dayEntity(day)
        statsDao.upsert(e.copy(bytesCompleted = e.bytesCompleted + sizeBytes, filesCompleted = e.filesCompleted + 1))
    }

    suspend fun recordFileFailed() {
        val day = localDayStart()
        val e = dayEntity(day)
        statsDao.upsert(e.copy(filesFailed = e.filesFailed + 1))
    }

    suspend fun recordRetries(n: Int = 1) {
        val day = localDayStart()
        val e = dayEntity(day)
        statsDao.upsert(e.copy(retryCount = e.retryCount + n))
    }

    /* ------------------------- queries (spec §44) ------------------------- */

    data class RangeStats(
        val bytes: Long, val files: Int, val failed: Int, val retries: Int,
        val avgSpeedBps: Double, val peakSpeedBps: Double, val activeMs: Long,
    )

    suspend fun range(fromDayEpoch: Long): RangeStats {
        val rows = statsDao.since(fromDayEpoch)
        if (rows.isEmpty()) return RangeStats(0, 0, 0, 0, 0.0, 0.0, 0)
        val bytes = rows.sumOf { it.bytesCompleted }
        val samples = rows.sumOf { it.speedSampleCount }
        val sumSpeed = rows.sumOf { it.sumSpeedSamplesBps }
        return RangeStats(
            bytes = bytes,
            files = rows.sumOf { it.filesCompleted },
            failed = rows.sumOf { it.filesFailed },
            retries = rows.sumOf { it.retryCount },
            avgSpeedBps = if (samples == 0) 0.0 else sumSpeed / samples,
            peakSpeedBps = rows.maxOf { it.peakSpeedBps },
            activeMs = rows.sumOf { it.activeDownloadMs },
        )
    }

    suspend fun bestSession(): DownloadSessionEntity? =
        sessionDao.observeRecent(100).let { null } // computed in repo flow; kept simple

    companion object {
        fun localDayStart(now: Long = System.currentTimeMillis()): Long {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        fun daysAgo(n: Int): Long = localDayStart(System.currentTimeMillis() - n * 86_400_000L)
    }
}

/**
 * Prediction Engine (spec §60): approximate — never a promise.
 * Uses the user's own history when available.
 */
object PredictionEngine {

    data class Forecast(
        val availableSeconds: Long,
        val expectedSpeedBps: Double,
        val expectedBytes: Long,
        val expectedCompletedFiles: Int,
        val etaPerFileSec: Map<Long, Long>,
        val filesLikelyComplete: List<Long>,
        val filesLikelyMissed: List<Long>,
    )

    fun forecast(
        queued: List<com.tdm.app.data.db.DownloadTaskEntity>,
        availableSeconds: Long,
        historicalSpeedBps: Double,
        concurrency: Int,
    ): Forecast {
        val speed = if (historicalSpeedBps > 1.0) historicalSpeedBps * maxOf(1, concurrency) * 0.85
        else 2.5 * 1024 * 1024 * maxOf(1, concurrency) * 0.85
        val expectedBytes = (speed * availableSeconds).toLong().coerceAtLeast(0)

        var budget = expectedBytes
        val eta = mutableMapOf<Long, Long>()
        val complete = mutableListOf<Long>()
        val missed = mutableListOf<Long>()
        var t = 0.0
        for (task in queued) {
            val remaining = (task.size - task.downloadedBytes).coerceAtLeast(0)
            val dt = remaining / speed
            t += dt
            eta[task.id] = t.toLong()
            if (budget >= remaining) {
                budget -= remaining
                complete.add(task.id)
            } else {
                missed.add(task.id)
                budget = 0
            }
        }
        return Forecast(
            availableSeconds = availableSeconds,
            expectedSpeedBps = speed,
            expectedBytes = expectedBytes,
            expectedCompletedFiles = complete.size,
            etaPerFileSec = eta,
            filesLikelyComplete = complete,
            filesLikelyMissed = missed,
        )
    }
}
