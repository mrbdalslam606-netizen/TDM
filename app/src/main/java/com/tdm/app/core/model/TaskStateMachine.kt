package com.tdm.app.core.model

/**
 * Validated state transitions (spec §12 "يمكن إضافة حالات داخلية أخرى عند الحاجة").
 * Kept pure & testable. Engine must call [canTransition] before every status write.
 */
object TaskStateMachine {

    private val allowed: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.DISCOVERED to setOf(
            TaskStatus.QUEUED, TaskStatus.CANCELED, TaskStatus.DISCOVERED
        ),
        TaskStatus.QUEUED to setOf(
            TaskStatus.STARTING, TaskStatus.PAUSED, TaskStatus.CANCELED, TaskStatus.QUEUED
        ),
        TaskStatus.STARTING to setOf(
            TaskStatus.DOWNLOADING, TaskStatus.RETRY_WAIT, TaskStatus.PAUSING,
            TaskStatus.FAILED, TaskStatus.QUEUED
        ),
        TaskStatus.DOWNLOADING to setOf(
            TaskStatus.PAUSING, TaskStatus.RETRY_WAIT, TaskStatus.FAILED,
            TaskStatus.COMPLETED, TaskStatus.INSUFFICIENT_STORAGE
        ),
        TaskStatus.PAUSING to setOf(
            TaskStatus.PAUSED, TaskStatus.QUEUED
        ),
        TaskStatus.PAUSED to setOf(
            TaskStatus.QUEUED, TaskStatus.STARTING, TaskStatus.CANCELED
        ),
        TaskStatus.RETRY_WAIT to setOf(
            TaskStatus.QUEUED, TaskStatus.STARTING, TaskStatus.FAILED, TaskStatus.CANCELED
        ),
        TaskStatus.FAILED to setOf(
            TaskStatus.QUEUED, TaskStatus.STARTING, TaskStatus.CANCELED
        ),
        TaskStatus.INSUFFICIENT_STORAGE to setOf(
            TaskStatus.QUEUED, TaskStatus.CANCELED, TaskStatus.INSUFFICIENT_STORAGE
        ),
        TaskStatus.RECOVERY_PENDING to setOf(
            TaskStatus.QUEUED, TaskStatus.PAUSED, TaskStatus.FAILED
        ),
        TaskStatus.COMPLETED to emptySet(),
        TaskStatus.CANCELED to emptySet(),
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean =
        from == to || allowed[from]?.contains(to) == true

    /** Terminal-ish statuses that must never be re-entered by auto logic (only by explicit user action). */
    fun isTerminal(s: TaskStatus): Boolean =
        s == TaskStatus.COMPLETED || s == TaskStatus.CANCELED

    /** Validate + return target, or null when rejected (caller logs). */
    fun validate(from: TaskStatus, to: TaskStatus): TaskStatus? =
        if (canTransition(from, to)) to else null
}
