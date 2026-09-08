package com.tdm.app.core.concurrency

import java.util.concurrent.atomic.AtomicLong

/**
 * Shared speed limiter (spec §11): global limit applies to all workers,
 * changeable at runtime WITHOUT restarting the engine.
 * Implemented as a rolling-window byte budget; workers call [acquire] per chunk.
 */
class RateLimiter(initialBps: Long) {

    private val limitBps = AtomicLong(initialBps)
    private val windowStartMs = AtomicLong(System.currentTimeMillis())
    private val bytesThisWindow = AtomicLong(0)

    fun setLimit(bps: Long) { limitBps.set(bps.coerceAtLeast(0)) }
    fun limitBps(): Long = limitBps.get()
    fun isUnlimited(): Boolean = limitBps.get() == 0L

    /**
     * Blocks until [bytes] may proceed. Unlimited → no-op.
     * Fair enough for 512 KiB chunks at MB/s rates.
     */
    suspend fun acquire(bytes: Int) {
        val limit = limitBps.get()
        if (limit <= 0) return
        val windowMs = 1000L
        while (true) {
            val now = System.currentTimeMillis()
            var start = windowStartMs.get()
            if (now - start >= windowMs) {
                if (windowStartMs.compareAndSet(start, now)) bytesThisWindow.set(0)
            }
            val used = bytesThisWindow.get()
            if (used + bytes <= limit) {
                if (bytesThisWindow.compareAndSet(used, used + bytes)) return
            } else {
                val waitMs = windowMs - (now - windowStartMs.get()).coerceAtLeast(0)
                if (waitMs > 0) kotlinx.coroutines.delay(waitMs.coerceAtMost(windowMs)) else return
            }
        }
    }
}
