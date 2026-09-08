package com.tdm.app.core.engine

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Cross-process heartbeat (spec §33): the engine writes a JSON heartbeat into app-private
 * storage; the watchdog lives in a DIFFERENT process (:watchdog) and reads it.
 * If the engine process dies, the file stops updating — the watchdog detects and recovers.
 */
class Heartbeat(context: Context) {

    private val file = File(context.filesDir, "engine_heartbeat.json")

    fun write(
        state: String,
        activeDownloads: Int,
        lastProgressBytes: Long,
        tdlibState: String,
        queueCount: Int,
        recoveryAttempts: Int = 0,
    ) {
        val o = JSONObject()
        o.put("ts", System.currentTimeMillis())
        o.put("state", state)
        o.put("active", activeDownloads)
        o.put("progressBytes", lastProgressBytes)
        o.put("tdlib", tdlibState)
        o.put("queue", queueCount)
        o.put("recoveryAttempts", recoveryAttempts)
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString()) // fallback
                tmp.delete()
            }
        }
    }

    data class Snapshot(
        val ts: Long,
        val state: String,
        val active: Int,
        val progressBytes: Long,
        val tdlib: String,
        val queue: Int,
        val recoveryAttempts: Int,
    )

    fun read(): Snapshot? {
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            Snapshot(
                ts = o.getLong("ts"),
                state = o.optString("state"),
                active = o.optInt("active"),
                progressBytes = o.optLong("progressBytes"),
                tdlib = o.optString("tdlib"),
                queue = o.optInt("queue"),
                recoveryAttempts = o.optInt("recoveryAttempts"),
            )
        }.getOrNull()
    }

    companion object {
        /** Stale threshold: engine considered dead after this without a heartbeat. */
        const val STALE_MS = 45_000L
    }
}
