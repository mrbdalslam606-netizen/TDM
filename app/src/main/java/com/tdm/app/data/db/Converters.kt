package com.tdm.app.data.db

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

/* ---------------- Value objects used across DB & core ---------------- */

data class FileFilter(
    val video: Boolean = true,
    val documents: Boolean = true,
    val archives: Boolean = true,
    val images: Boolean = true,
    val audio: Boolean = true,
    val extensions: List<String> = emptyList(),   // lowercase, without dot; empty = any
    val filenameContains: String = "",
    val minSizeBytes: Long = 0L,
    val maxSizeBytes: Long = Long.MAX_VALUE,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("video", video); o.put("documents", documents); o.put("archives", archives)
        o.put("images", images); o.put("audio", audio)
        o.put("ext", JSONArray(extensions))
        o.put("contains", filenameContains)
        o.put("min", minSizeBytes); o.put("max", if (maxSizeBytes == Long.MAX_VALUE) -1 else maxSizeBytes)
        return o.toString()
    }
    companion object {
        fun fromJson(s: String?): FileFilter {
            if (s.isNullOrBlank()) return FileFilter()
            return runCatching {
                val o = JSONObject(s)
                val ext = mutableListOf<String>()
                val arr = o.optJSONArray("ext") ?: JSONArray()
                for (i in 0 until arr.length()) ext.add(arr.getString(i).lowercase())
                FileFilter(
                    video = o.optBoolean("video", true),
                    documents = o.optBoolean("documents", true),
                    archives = o.optBoolean("archives", true),
                    images = o.optBoolean("images", true),
                    audio = o.optBoolean("audio", true),
                    extensions = ext,
                    filenameContains = o.optString("contains", ""),
                    minSizeBytes = o.optLong("min", 0L),
                    maxSizeBytes = o.optLong("max", -1L).let { if (it < 0) Long.MAX_VALUE else it },
                )
            }.getOrDefault(FileFilter())
        }
    }
}

data class RetryPolicy(
    val unlimited: Boolean = true,
    val maxAttempts: Int = 5,
    val mode: Mode = Mode.EXPONENTIAL,
    val baseDelaySec: Int = 30,
) {
    enum class Mode { FIXED, EXPONENTIAL }
    fun toJson(): String {
        val o = JSONObject()
        o.put("unlimited", unlimited); o.put("max", maxAttempts)
        o.put("mode", mode.name); o.put("base", baseDelaySec)
        return o.toString()
    }
    companion object {
        fun fromJson(s: String?): RetryPolicy {
            if (s.isNullOrBlank()) return RetryPolicy()
            return runCatching {
                val o = JSONObject(s)
                RetryPolicy(
                    unlimited = o.optBoolean("unlimited", true),
                    maxAttempts = o.optInt("max", 5),
                    mode = runCatching { Mode.valueOf(o.optString("mode", "EXPONENTIAL")) }.getOrDefault(Mode.EXPONENTIAL),
                    baseDelaySec = o.optInt("base", 30),
                )
            }.getOrDefault(RetryPolicy())
        }
    }
}

enum class NetworkPolicy { ANY, WIFI_ONLY, MOBILE_ONLY }

enum class QueueMode {
    FIFO,                 // arrival order
    TELEGRAM_MESSAGE,     // ascending message id within source
    FILENAME_NATURAL,     // natural filename ordering (1,2,3,10) — critical (spec §13)
    DATE_TIME,            // message date
    FILE_SIZE_ASC,
    FILE_SIZE_DESC,
    PRIORITY,             // priority weight then arrival
    MANUAL,               // manualOrderKey only
    SMART,                // SmartQueue scoring (spec §17)
}

enum class SourceType { CHANNEL, GROUP, CHAT, INBOX_SAVED, INBOX_CHAT }

enum class StartFromMode { NOW, LAST_N, SINCE_DATE, RANGE, ALL }

/* ---------------- Type converters ---------------- */

class Converters {
    @TypeConverter fun filterToJson(f: FileFilter?): String = f?.toJson() ?: ""
    @TypeConverter fun jsonToFilter(s: String?): FileFilter = FileFilter.fromJson(s)
    @TypeConverter fun retryToJson(r: RetryPolicy?): String = r?.toJson() ?: ""
    @TypeConverter fun jsonToRetry(s: String?): RetryPolicy = RetryPolicy.fromJson(s)
    @TypeConverter fun netToJson(p: NetworkPolicy?): String = p?.name ?: ""
    @TypeConverter fun jsonToNet(s: String?): NetworkPolicy =
        runCatching { NetworkPolicy.valueOf(s ?: "ANY") }.getOrDefault(NetworkPolicy.ANY)
    @TypeConverter fun queueModeToJson(m: QueueMode?): String = m?.name ?: ""
    @TypeConverter fun jsonToQueueMode(s: String?): QueueMode =
        runCatching { QueueMode.valueOf(s ?: "FIFO") }.getOrDefault(QueueMode.FIFO)
    @TypeConverter fun sourceTypeToJson(t: SourceType?): String = t?.name ?: ""
    @TypeConverter fun jsonToSourceType(s: String?): SourceType =
        runCatching { SourceType.valueOf(s ?: "CHANNEL") }.getOrDefault(SourceType.CHANNEL)
    @TypeConverter fun startFromToJson(m: StartFromMode?): String = m?.name ?: ""
    @TypeConverter fun jsonToStartFrom(s: String?): StartFromMode =
        runCatching { StartFromMode.valueOf(s ?: "NOW") }.getOrDefault(StartFromMode.NOW)
}
