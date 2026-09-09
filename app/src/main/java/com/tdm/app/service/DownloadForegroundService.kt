package com.tdm.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tdm.app.R
import com.tdm.app.di.AppContainer
import com.tdm.app.core.engine.DownloadEngine
import com.tdm.app.core.engine.EngineStateHolder
import com.tdm.app.core.engine.MonitorEngine
import com.tdm.app.core.model.EngineRunState
import com.tdm.app.core.recovery.RecoveryManager
import com.tdm.app.core.reliability.ShizukuCapabilityManager
import com.tdm.app.data.db.TdmDatabase
import com.tdm.app.data.repo.SettingsRepository
import com.tdm.app.telegram.TelegramClientPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground Service hosting the Download Engine (spec §31).
 * Lifecycle: onCreate → reconcile (spec §25) → engine.start() → monitor loop.
 * Notification shows speed / progress / queue count with Pause/Resume All actions.
 */
class DownloadForegroundService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.tdm.app.action.START_ENGINE"
        const val ACTION_STOP = "com.tdm.app.action.STOP_ENGINE"
        const val EXTRA_REASON = "reason"

        fun start(context: Context, reason: String) {
            val i = Intent(context, DownloadForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DownloadForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private lateinit var container: AppContainer
    private lateinit var engine: DownloadEngine
    private lateinit var monitor: MonitorEngine
    private lateinit var recovery: RecoveryManager
    private lateinit var notifications: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        notifications = NotificationHelper(this)

        startForeground(
            NotificationHelper.NOTIF_FGS_ID,
            notifications.buildForeground(0, "", "Starting engine…", "", 0, false)
        )

        engine = container.engine(this)
        monitor = container.monitor(this)
        recovery = container.recovery
        container.engineState = engine.stateHolder

        lifecycleScope.launch {
            val reason = intentReason ?: "service"
            // Startup reconciliation BEFORE engine start (spec §25, §78)
            val result = recovery.reconcileOnStartup(reason)
            engine.start()
            container.startWatchdog()

            // Live Telegram updates must enter the same durable ingestion pipeline as scans.
            launch {
                container.telegram.incomingMessages.collect { message ->
                    runCatching { monitor.onMessage(message) }
                }
            }

            // monitor loop: sources + inbox (spec §63) — persistent bookmarks
            var lastScan = 0L
            var lastNotif = 0L
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (now - lastScan >= 60_000) {
                    lastScan = now
                    val loggedIn = container.settingsRepository.settings.first().loggedIn
                    if (container.telegram.isInitialized() && loggedIn) {
                        runCatching { monitor.scanAll() }
                    }
                }
                // refresh notification every 3s
                if (now - lastNotif >= 3000) {
                    lastNotif = now
                    updateNotification()
                }
                delay(1000)
            }
        }
    }

    private var intentReason: String = "service"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> intentReason = intent.getStringExtra(EXTRA_REASON) ?: "start"
            ACTION_STOP -> {
                engine.stop()
                container.stopWatchdog()
                stopSelf()
            }
        }
        return START_STICKY // engine should be rebuilt from persistent state (spec §81)
    }

    private fun updateNotification() {
        val s = engine.stateHolder.snapshot.value
        val live = engine.stateHolder.liveProgress.value.values
            .firstOrNull { it.status == com.tdm.app.core.model.TaskStatus.DOWNLOADING }
        val speedText = formatSpeed(s.totalSpeedBps)
        val progressText = if (s.overallTotal > 0)
            "${formatBytes(s.overallBytes)} / ${formatBytes(s.overallTotal)}"
        else if (s.sessionBytes > 0) formatBytes(s.sessionBytes) else ""
        notifications.updateForeground(
            notifications.buildForeground(
                s.activeCount, speedText, progressText,
                live?.let { "" } ?: s.currentFile, s.queuedCount,
                s.runState == EngineRunState.PAUSED_ALL,
            )
        )
    }

    override fun onDestroy() {
        engine.stop()
        container.stopWatchdog()
        super.onDestroy()
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun formatSpeed(bps: Double): String = formatBytes(bps.toLong()) + "/s"
}
