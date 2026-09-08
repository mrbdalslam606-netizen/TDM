package com.tdm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tdm.app.MainActivity
import com.tdm.app.R

/** Notification helper (spec §31, §51): useful content, not "App is running". */
class NotificationHelper(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_EVENTS = "events"
        const val CHANNEL_WATCHDOG = "watchdog"
        const val NOTIF_FGS_ID = 1001
        const val NOTIF_EVENT_BASE = 2000
    }

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_downloads_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_events_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCHDOG, context.getString(R.string.channel_watchdog), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.channel_watchdog_desc)
                setShowBadge(false)
            }
        )
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun action(action: String, title: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, EngineActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun buildForeground(
        activeCount: Int,
        speedText: String,
        progressText: String,
        currentFile: String,
        queuedCount: Int,
        pausedAll: Boolean,
    ): Notification {
        val title = if (activeCount > 0)
            context.getString(R.string.notif_downloading, activeCount, speedText)
        else context.getString(R.string.notif_idle)

        val text = buildString {
            if (progressText.isNotBlank()) append(progressText)
            if (currentFile.isNotBlank()) {
                if (isNotEmpty()) append(" — ")
                append(currentFile)
            }
            if (queuedCount > 0) {
                if (isNotEmpty()) append(" — ")
                append("Queue: ").append(queuedCount)
            }
        }

        val b = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text.ifBlank { " " })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (activeCount > 0 || queuedCount > 0) {
            b.addAction(0, context.getString(R.string.action_pause_all), action(EngineActionReceiver.ACTION_PAUSE_ALL, "", 1))
            b.addAction(0, context.getString(R.string.action_resume_all), action(EngineActionReceiver.ACTION_RESUME_ALL, "", 2))
        }
        return b.build()
    }

    fun updateForeground(n: Notification) {
        nm.notify(NOTIF_FGS_ID, n)
    }

    fun event(title: String, text: String) {
        val b = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        nm.notify(NOTIF_EVENT_BASE + (title.hashCode() and 0xFFFF), b.build())
    }

    fun watchdog() {
        val b = NotificationCompat.Builder(context, CHANNEL_WATCHDOG)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(context.getString(R.string.notif_watchdog))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
        nm.notify(3001, b.build())
    }
}
