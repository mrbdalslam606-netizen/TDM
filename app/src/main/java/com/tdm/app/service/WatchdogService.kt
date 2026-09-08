package com.tdm.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tdm.app.R
import com.tdm.app.di.AppContainer
import com.tdm.app.core.logging.LogRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watchdog Service (spec §33): runs in the ":watchdog" PROCESS — it must NOT share the
 * engine process, otherwise it dies together with the engine.
 * Monitors: engine process, foreground service, heartbeat freshness, TDLib state, queue activity.
 * Recovery policy: backoff + failure counter + cooldown + reason logging (spec §33, §38).
 */
class WatchdogService : LifecycleService() {

    private lateinit var container: AppContainer
    private var lastProgressBytes: Long = -1
    private var lastProgressChange: Long = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        val notifications = NotificationHelper(this)
        startForeground(3002, buildWatchdogNotification(notifications))

        lifecycleScope.launch {
            while (isActive) {
                checkHealth()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun buildWatchdogNotification(nm: NotificationHelper): android.app.Notification {
        val b = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_WATCHDOG)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_watchdog))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
        return b.build()
    }

    private suspend fun checkHealth() {
        val recovery = container.recovery
        val health = recovery.engineHealth()
        val settings = container.settingsRepository.current()

        if (!settings.reliabilityWatchdogEnabled) return

        val engineShouldRun = settings.loggedIn // engine is wanted whenever user is logged in

        if (engineShouldRun && !health.healthy) {
            val stalledProgress = lastProgressBytes == health.heartbeat?.progressBytes
            if (stalledProgress && lastProgressChange != 0L &&
                System.currentTimeMillis() - lastProgressChange > STALL_MS
            ) {
                LogRepo.log(
                    container.database, "WATCHDOG", "WARN",
                    "engine progress stalled (${health.heartbeat?.progressBytes} bytes) — recovery"
                )
            }

            if (recovery.isRecoveryFailed()) {
                LogRepo.log(
                    container.database, "WATCHDOG", "ERROR",
                    "recovery failed — surface to user instead of restart loop (spec §38)"
                )
                return
            }

            if (recovery.shouldAttemptRecovery()) {
                recovery.recordRecoveryAttempt()
                // 1) Shizuku path (optional, when available — spec §34/§35)
                val shizuku = container.shizuku
                var restarted = false
                if (settings.reliabilityShizukuEnabled && shizuku.canPerformRecoveryOperation()) {
                    restarted = shizuku.restartEngineService()
                    LogRepo.log(
                        container.database, "SHIZUKU", "INFO",
                        "shizuku engine restart attempted: $restarted"
                    )
                }
                // 2) standard FGS start (allowed here: watchdog holds an FGS → app not background)
                if (!restarted) {
                    runCatching {
                        DownloadForegroundService.start(this, "watchdog-recovery")
                    }
                }
                lastProgressChange = System.currentTimeMillis()
            }
        } else {
            // healthy — track progress freshness
            val p = health.heartbeat?.progressBytes ?: 0
            if (p != lastProgressBytes) {
                lastProgressBytes = p
                lastProgressChange = System.currentTimeMillis()
            }
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 30_000L
        const val STALL_MS = 5 * 60_000L

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, WatchdogService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }
    }
}
