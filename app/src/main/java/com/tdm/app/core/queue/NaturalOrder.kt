package com.tdm.app.core.queue

import java.util.Locale

/**
 * Natural filename ordering (spec §13): Episode 1 → 2 → 3 → 10, never 1 → 10 → 2 → 3.
 * Digit runs compare numerically; text runs compare case-insensitively.
 */
object NaturalOrder {

    private val digitSplit = Regex("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")

    fun compare(a: String, b: String): Int {
        val pa = split(a)
        val pb = split(b)
        val n = minOf(pa.size, pb.size)
        for (i in 0 until n) {
            val ta = pa[i]
            val tb = pb[i]
            val c = if (isNumeric(ta) && isNumeric(tb)) {
                val byValue = ta.toLong().compareTo(tb.toLong())
                if (byValue != 0) byValue else ta.compareTo(tb) // equal value: "02" sorts before "2" (stable)
            } else {
                ta.lowercase(Locale.ROOT).compareTo(tb.lowercase(Locale.ROOT))
            }
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    private fun split(s: String): List<String> = digitSplit.split(s)

    private fun isNumeric(s: String) = s.isNotEmpty() && s[0].isDigit()

    fun <T> sortedBy(items: List<T>, nameOf: (T) -> String): List<T> =
        items.sortedWith(Comparator { x, y -> compare(nameOf(x), nameOf(y)) })
}
