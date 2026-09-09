package com.tdm.app.core.scheduler

import java.util.Calendar

/**
 * Schedule matching (spec §7): local phone time, multiple windows, overnight windows,
 * days-of-week bitmask (bit0 = Monday).
 *
 * Spec §74 semantics:
 *  - App started 07:00 with window 06:00–09:00 → download immediately (window still active).
 *  - Window end must be respected (Stop Immediately / Finish Current handled by engine).
 */
object ScheduleMatcher {

    const val MON = 0b0000001
    const val TUE = 0b0000010
    const val WED = 0b0000100
    const val THU = 0b0001000
    const val FRI = 0b0010000
    const val SAT = 0b0100000
    const val SUN = 0b1000000
    const val ALL_DAYS = 0b1111111

    data class WindowRef(
        val daysBitmask: Int,
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int, // may be < start → overnight window
    )

    /** Is [nowMillis] (local time) inside any window? */
    fun isActive(windows: List<WindowRef>, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dowBits = bitForCalendarDay(cal.get(Calendar.DAY_OF_WEEK))
        val prevDayBits = bitForCalendarDay(cal.get(Calendar.DAY_OF_WEEK) - 1 + if (cal.get(Calendar.DAY_OF_WEEK) - 1 < 1) 7 else 0)

        for (w in windows) {
            if (w.startMinuteOfDay == w.endMinuteOfDay) continue
            if (w.startMinuteOfDay < w.endMinuteOfDay) {
                // same-day window
                if (w.daysBitmask and dowBits != 0 && minuteOfDay in w.startMinuteOfDay until w.endMinuteOfDay) return true
            } else {
                // overnight window (e.g. 23:00 → 01:00)
                if (w.daysBitmask and dowBits != 0 && minuteOfDay >= w.startMinuteOfDay) return true
                if (w.daysBitmask and prevDayBits != 0 && minuteOfDay < w.endMinuteOfDay) return true
            }
        }
        return false
    }

    /** Epoch millis of the next window start, or null if none scheduled. */
    fun nextWindowStart(windows: List<WindowRef>, nowMillis: Long = System.currentTimeMillis()): Long? {
        if (windows.isEmpty()) return null
        var best: Long? = null
        // deterministic day-by-day scan (up to 8 days ahead) — no minute loops
        for (d in 0..8) {
            val c = Calendar.getInstance().apply { timeInMillis = nowMillis }
            c.add(Calendar.DAY_OF_YEAR, d)
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val bits = bitForCalendarDay(c.get(Calendar.DAY_OF_WEEK))
            for (w in windows) {
                if (w.daysBitmask and bits == 0) continue
                val startMs = c.timeInMillis + w.startMinuteOfDay * 60_000L
                if (startMs > nowMillis && (best == null || startMs < best)) best = startMs
            }
        }
        return best
    }

    /** Seconds remaining inside the ACTIVE window at [nowMillis]; 0 when none active. */
    fun remainingSeconds(windows: List<WindowRef>, nowMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dowBits = bitForCalendarDay(cal.get(Calendar.DAY_OF_WEEK))
        val prevDayBits = bitForCalendarDay(
            ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        )
        var bestEnd: Int? = null
        for (w in windows) {
            if (w.startMinuteOfDay == w.endMinuteOfDay) continue
            if (w.startMinuteOfDay < w.endMinuteOfDay) {
                if (w.daysBitmask and dowBits != 0 && minuteOfDay in w.startMinuteOfDay until w.endMinuteOfDay) {
                    val rem = w.endMinuteOfDay - minuteOfDay
                    if (bestEnd == null || rem < bestEnd) bestEnd = rem
                }
            } else {
                if (w.daysBitmask and dowBits != 0 && minuteOfDay >= w.startMinuteOfDay) {
                    val rem = (24 * 60 - minuteOfDay) + w.endMinuteOfDay
                    if (bestEnd == null || rem < bestEnd) bestEnd = rem
                }
                if (w.daysBitmask and prevDayBits != 0 && minuteOfDay < w.endMinuteOfDay) {
                    val rem = w.endMinuteOfDay - minuteOfDay
                    if (bestEnd == null || rem < bestEnd) bestEnd = rem
                }
            }
        }
        return (bestEnd ?: 0) * 60L - cal.get(Calendar.SECOND)
    }

    /** Calendar.SUNDAY=1..SATURDAY=7 → our bit layout with Monday as bit0. */
    fun bitForCalendarDay(calendarDay: Int): Int = when (calendarDay) {
        Calendar.MONDAY -> MON
        Calendar.TUESDAY -> TUE
        Calendar.WEDNESDAY -> WED
        Calendar.THURSDAY -> THU
        Calendar.FRIDAY -> FRI
        Calendar.SATURDAY -> SAT
        Calendar.SUNDAY -> SUN
        else -> MON
    }
}
