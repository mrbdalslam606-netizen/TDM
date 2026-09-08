package com.tdm.app.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tdm.app.di.AppContainer
import com.tdm.app.core.logging.LogRepo
import kotlinx.coroutines.runBlocking

/**
 * BootReceiver (spec §40): Android 12 forbids starting FGS directly from BOOT_COMPLETED.
 * Solution: schedule an EXACT alarm a few seconds out — exact-alarm receivers ARE exempt
 * from the background FGS-start restriction on Android 12.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        runBlocking {
            val settings = AppContainer.get(context).settingsRepository.current()
            if (!settings.autoStartAfterBoot || !settings.loggedIn) return@runBlocking
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                context, 42,
                Intent(context, WatchdogAlarmReceiver::class.java).setAction(WatchdogAlarmReceiver.ACTION_BOOT_RECOVERY),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val at = System.currentTimeMillis() + 12_000L
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at - android.os.SystemClock.elapsedRealtime(), 30_000L, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            LogRepo.log(AppContainer.get(context).database, "RECOVERY", "INFO", "boot recovery alarm scheduled")
        }
    }
}

/**
 * WatchdogAlarmReceiver (spec §33): alarm-driven health checks independent of any process.
 * Each firing is a fresh chance to detect engine death and restart it (exact alarm → FGS exempt).
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_HEALTH_CHECK = "com.tdm.app.action.WATCHDOG_HEALTH"
        const val ACTION_BOOT_RECOVERY = "com.tdm.app.action.BOOT_RECOVERY"
        fun schedule(context: Context, intervalMs: Long = 120_000L) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                context, 43,
                Intent(context, WatchdogAlarmReceiver::class.java).setAction(ACTION_HEALTH_CHECK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val at = System.currentTimeMillis() + intervalMs
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, intervalMs / 2, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val container = AppContainer.get(context)
        when (intent.action) {
            ACTION_BOOT_RECOVERY -> {
                DownloadForegroundService.start(context, "boot")
                runBlocking {
                    LogRepo.log(container.database, "RECOVERY", "INFO", "boot recovery: engine start requested")
                }
            }
            ACTION_HEALTH_CHECK -> {
                val health = container.recovery.engineHealth()
                if (!health.healthy) {
                    runBlocking {
                        LogRepo.log(
                            container.database, "WATCHDOG", "WARN",
                            "alarm health check: engine unhealthy (stale=${health.stale}) → restart"
                        )
                    }
                    if (container.recovery.shouldAttemptRecovery()) {
                        container.recovery.recordRecoveryAttempt()
                        DownloadForegroundService.start(context, "alarm-watchdog")
                    }
                }
            }
        }
        // reschedule the next check (self-perpetuating chain, survives process death)
        val watchdogEnabled = runBlocking { container.settingsRepository.current().reliabilityWatchdogEnabled }
        if (watchdogEnabled) {
            schedule(context)
        }
    }
}

/** Notification action buttons (Pause/Resume All) (spec §31). */
class EngineActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PAUSE_ALL = "com.tdm.app.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.tdm.app.action.RESUME_ALL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val container = AppContainer.get(context)
        when (intent.action) {
            ACTION_PAUSE_ALL -> container.engine(context).let { e ->
                kotlinx.coroutines.runBlocking { e.pauseAll() }
            }
            ACTION_RESUME_ALL -> container.engine(context).let { e ->
                kotlinx.coroutines.runBlocking { e.resumeAll() }
            }
        }
    }
}

/** Network state changes (spec §10): refresh monitor; engine gates tasks on next tick. */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppContainer.get(context).networkMonitor.refresh()
    }
}
