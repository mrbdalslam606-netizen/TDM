package com.tdm.app.core.retry

import com.tdm.app.data.db.RetryPolicy

/**
 * Retry Engine (spec §41): fixed or exponential delays, unlimited or N attempts.
 * FLOOD_WAIT is handled OUTSIDE this policy — Telegram's X seconds are respected strictly.
 */
object RetryEngine {

    data class Decision(
        val shouldRetry: Boolean,
        val delaySec: Long,
        val attempt: Int, // attempt number that will be used next
    )

    fun nextDelay(policy: RetryPolicy, retryCount: Int): Long {
        val base = policy.baseDelaySec.toLong().coerceAtLeast(1)
        return when (policy.mode) {
            RetryPolicy.Mode.FIXED -> base
            RetryPolicy.Mode.EXPONENTIAL -> {
                // 30s → 1m → 2m → 5m → 10m style growth, capped at 15m
                val d = base * (1L shl retryCount.coerceIn(0, 5))
                d.coerceAtMost(900)
            }
        }
    }

    fun decide(policy: RetryPolicy, retryCount: Int): Decision {
        val limitReached = !policy.unlimited && retryCount >= policy.maxAttempts
        return if (limitReached) {
            Decision(false, 0, retryCount)
        } else {
            Decision(true, nextDelay(policy, retryCount), retryCount + 1)
        }
    }

    /** FloodWait never goes through user policy — the server-mandated delay is used directly. */
    fun floodWaitDelaySec(serverSeconds: Int): Long = serverSeconds.coerceIn(1, 24 * 3600).toLong()
}
