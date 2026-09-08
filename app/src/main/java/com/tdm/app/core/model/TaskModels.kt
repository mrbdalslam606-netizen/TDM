package com.tdm.app.core.model

/**
 * Task lifecycle states (spec §12). Persisted in DB — RAM is never the source of truth.
 * Transitions are validated by [TaskStateMachine]; invalid transitions are logged and rejected.
 */
enum class TaskStatus {
    DISCOVERED,     // found by monitoring, not yet queued (waiting on duplicate-check / filter / auto-download off)
    QUEUED,         // accepted into queue, waiting for a slot
    STARTING,       // slot granted, TDLib download starting
    DOWNLOADING,    // active transfer
    PAUSING,        // graceful stop requested, flushing checkpoints
    PAUSED,         // stopped, resumable (pauseReason distinguishes who paused)
    RETRY_WAIT,     // failed attempt, waiting for nextRetryAt
    FAILED,         // exhausted retries or unrecoverable error — stays visible in Failed section
    COMPLETED,      // finalized atomically at destination
    CANCELED,       // removed by user
    INSUFFICIENT_STORAGE, // pre-flight space check failed; deferred per policy
    RECOVERY_PENDING,     // active before crash/reboot; reconciled into QUEUED/PAUSED on startup
}

/** Who/what requested a pause (spec §15) — Resume All must not lift manual pauses. */
enum class PauseReason {
    NONE,
    MANUAL,         // user paused this specific task — only this user action can resume it
    SYSTEM_GLOBAL,  // Pause All pressed (resumable by Resume All)
    SCHEDULE,       // outside any active window
    NETWORK,        // network policy not satisfied (e.g. Wi-Fi only, currently mobile)
    ENGINE,         // engine stopped / crash — will be reconciled
    STORAGE,        // destination unavailable / permission lost
    SPEED_LIMIT_OFF, // reserved for future per-profile limits
}

enum class TaskPriority(val weight: Int) {
    LOW(0), NORMAL(5), HIGH(10), CRITICAL(20);
}

/** Global engine session state exposed to UI and notification. */
enum class EngineRunState {
    IDLE,               // engine alive, nothing eligible to run now
    RUNNING_SCHEDULE,   // inside an active schedule window
    RUNNING_MANUAL,     // Download Now session
    PAUSED_ALL,         // user pressed Pause All
    WAITING_NETWORK,    // eligible tasks exist but network policy unsatisfied
    WAITING_SCHEDULE,   // tasks exist, next window known
    RECOVERY,           // reconciling after crash/reboot
    RECOVERY_FAILED,    // recovery exceeded backoff limits — user action required
    STOPPED,            // engine service not running
}
