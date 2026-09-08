package com.tdm.app.core.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Storage path templates (spec §27): {root}/{channel}/{date}/{filename}
 * Tokens: root, channel, date, year, month, day, filename
 * No absolute paths are ever persisted — only the template + SAF tree uri.
 */
object PathTemplate {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun resolve(
        template: String,
        channel: String,
        filename: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val d = Date(timestampMs)
        val parts = template.split('/').filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        for (p in parts) {
            when (p.lowercase().trim('{', '}')) {
                "root" -> Unit // root = SAF tree base, never a segment
                "channel" -> out.add(sanitize(channel))
                "date" -> out.add(dateFmt.format(d))
                "year" -> out.add(SimpleDateFormat("yyyy", Locale.US).format(d))
                "month" -> out.add(SimpleDateFormat("MM", Locale.US).format(d))
                "day" -> out.add(SimpleDateFormat("dd", Locale.US).format(d))
                "filename" -> out.add(sanitizeFilename(filename))
                else -> if (p.contains('{') && p.contains('}')) {
                    // unknown token → sanitized literal token text
                    out.add(sanitize(p))
                } else {
                    out.add(sanitize(p))
                }
            }
        }
        // last part must be the filename
        if (out.isEmpty() || out.last() != sanitizeFilename(filename)) {
            out.add(sanitizeFilename(filename))
        }
        return out
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.isBlank()) "unknown" else cleaned
    }

    fun sanitizeFilename(name: String): String {
        val cleaned = sanitize(name)
        return if (cleaned.length > 180) cleaned.take(180) else cleaned
    }

    fun namingResolve(template: String, original: String, channel: String): String =
        template
            .replace("{original_name}", original)
            .replace("{channel}", sanitize(channel))
            .let { if (it.isBlank()) original else it }
}
