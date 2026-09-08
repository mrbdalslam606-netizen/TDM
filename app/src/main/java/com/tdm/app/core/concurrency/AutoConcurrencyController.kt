package com.tdm.app.core.concurrency

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto Concurrency (spec §18, §20): measures throughput per concurrency level and adapts.
 * Required properties:
 *  - warm-up period per level before judging
 *  - measurement window (moving average)
 *  - minimum improvement threshold (e.g. 5% — below it, don't scale up; spec §75)
 *  - hysteresis + cooldown to prevent oscillation (3→4→3→4)
 *  - hard maximum cap
 */
class AutoConcurrencyController(
    private val maxConcurrency: Int = 5,
    private val minImprovementRatio: Double = 0.05,   // 5%
    private val warmUpSamples: Int = 8,               // samples (~8 * 5s = 40s)
    private val cooldownSamples: Int = 12,            // min samples between changes
    private val downgradeRatio: Double = 0.90,        // degrade >10% → go back
) {
    var current: Int = 1
        private set

    private val samples = ArrayDeque<Double>()
    private var samplesAtThisLevel = 0
    private var samplesSinceChange = 0
    private var bestThroughput = 0.0
    private var bestLevel = 1
    private var levelHistory = ArrayDeque<Int>()

    /** Feed a throughput sample (bytes/sec) measured while running at [current]. */
    fun onSample(throughputBps: Double) {
        if (throughputBps <= 0.0) return
        samples.addLast(throughputBps)
        if (samples.size > 20) samples.removeFirst()
        samplesAtThisLevel++
        samplesSinceChange++
        val avg = avg()
        if (avg > bestThroughput) {
            bestThroughput = avg
            bestLevel = current
        }
    }

    /** Called by the engine periodically; may return a NEW target concurrency. */
    fun tick(): Int {
        if (samplesAtThisLevel < warmUpSamples || samplesSinceChange < cooldownSamples) return current
        val avg = avg()

        // Degradation → step back toward the best-known level (spec §18: انخفض الأداء → ارجع)
        if (current > bestLevel && bestThroughput > 0 && avg < bestThroughput * 0.995) {
            changeTo(current - 1)
            return current
        }

        // Scale up only with meaningful improvement (≥ threshold) at this level
        if (current < maxConcurrency) {
            val reference = levelAvgHistory[current] ?: avg
            if (reference > 0) {
                val improvement = (avg - reference) / reference
                if (improvement >= minImprovementRatio) {
                    changeTo(current + 1)
                    return current
                }
            } else if (bestLevel > current) {
                // history says a higher level was better → walk up
                changeTo(current + 1)
                return current
            }
        }
        return current
    }

    fun maxAllowed(): Int = maxConcurrency

    fun forceLevel(level: Int) {
        changeTo(level.coerceIn(1, maxConcurrency))
    }

    /** Historical per-level average throughput — powers prediction (spec §60). */
    private val levelAvgHistory = mutableMapOf<Int, Double>()

    private fun avg(): Double = if (samples.isEmpty()) 0.0 else samples.average()

    private fun changeTo(newLevel: Int) {
        // record history for this level before leaving
        levelAvgHistory[current] = avg()
        levelHistory.addLast(current)
        if (levelHistory.size > 10) levelHistory.removeFirst()
        current = newLevel.coerceIn(1, maxConcurrency)
        samplesAtThisLevel = 0
        samplesSinceChange = 0
        samples.clear()
    }

    /** Oscillation guard used by tests: true if the last changes flipped between two levels repeatedly. */
    fun isOscillating(): Boolean {
        if (levelHistory.size < 4) return false
        val last = levelHistory.takeLast(4)
        return abs(last[0] - last[1]) > 0 && last[0] == last[2] && last[1] == last[3]
    }

    fun snapshot(): Snapshot = Snapshot(
        current = current,
        bestLevel = bestLevel,
        bestThroughputBps = bestThroughput,
        avgThroughputBps = avg(),
        samples = samplesAtThisLevel,
    )

    data class Snapshot(
        val current: Int,
        val bestLevel: Int,
        val bestThroughputBps: Double,
        val avgThroughputBps: Double,
        val samples: Int,
    )

    companion object {
        fun clamp(desired: Int, max: Int): Int = min(max(desired, 1), max(1, max))
    }
}
