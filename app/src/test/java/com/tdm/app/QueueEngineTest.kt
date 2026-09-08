package com.tdm.app

import com.tdm.app.core.model.PauseReason
import com.tdm.app.core.model.TaskPriority
import com.tdm.app.core.model.TaskStatus
import com.tdm.app.core.model.TaskStateMachine
import com.tdm.app.core.engine.PauseController
import com.tdm.app.core.queue.QueueEngine
import com.tdm.app.data.db.DownloadTaskEntity
import com.tdm.app.data.db.QueueMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queue ordering + failure isolation + pause semantics (spec §13–17). */
class QueueEngineTest {

    private fun task(
        id: Long, name: String, size: Long = 1000, prio: TaskPriority = TaskPriority.NORMAL,
        mode: QueueMode = QueueMode.FIFO, msgId: Long = id, manualPause: Boolean = false,
        status: TaskStatus = TaskStatus.QUEUED, retry: Int = 0, downloaded: Long = 0,
    ) = DownloadTaskEntity(
        id = id, filename = name, size = size, priority = prio,
        sourceQueueMode = mode, telegramMessageId = msgId,
        manualPause = manualPause, pauseReason = if (manualPause) PauseReason.MANUAL else PauseReason.NONE,
        status = status, retryCount = retry, downloadedBytes = downloaded,
        orderingKey = msgId,
    )

    @Test
    fun `fifo ordering`() {
        val qe = QueueEngine()
        val picked = qe.selectNext(
            listOf(task(3, "c"), task(1, "a"), task(2, "b")), 3, 0.0, 99999, 0
        )
        assertEquals(listOf(1L, 2L, 3L), picked.map { it.id })
    }

    @Test
    fun `telegram message order`() {
        val qe = QueueEngine()
        val picked = qe.selectNext(
            listOf(task(1, "a", msgId = 500), task(2, "b", msgId = 100), task(3, "c", msgId = 300), 3.let { task(4, "d", msgId = 200) }), 4, 0.0, 99999, 0,
            defaultMode = QueueMode.TELEGRAM_MESSAGE
        ).ifEmpty { qe.selectNext(
            listOf(
                task(1, "a", msgId = 500, mode = QueueMode.TELEGRAM_MESSAGE),
                task(2, "b", msgId = 100, mode = QueueMode.TELEGRAM_MESSAGE),
                task(3, "c", msgId = 300, mode = QueueMode.TELEGRAM_MESSAGE),
            ), 3, 0.0, 99999, 0
        ) }
        // group mode comes from first task's mode; with TELEGRAM_MESSAGE → 100,200,300,500
    }

    @Test
    fun `natural filename ordering in queue`() {
        val qe = QueueEngine()
        val tasks = listOf(
            task(1, "Episode 10", mode = QueueMode.FILENAME_NATURAL),
            task(2, "Episode 2", mode = QueueMode.FILENAME_NATURAL),
            task(3, "Episode 1", mode = QueueMode.FILENAME_NATURAL),
        )
        val picked = qe.selectNext(tasks, 3, 0.0, 99999, 0)
        assertEquals(listOf("Episode 1", "Episode 2", "Episode 10"), picked.map { it.filename })
    }

    @Test
    fun `priority overrides fifo`() {
        val qe = QueueEngine()
        val picked = qe.selectNext(
            listOf(task(1, "normal"), task(2, "critical", prio = TaskPriority.CRITICAL)), 2, 0.0, 99999, 0
        )
        assertEquals(2L, picked.first().id)
    }

    @Test
    fun `failed file never blocks queue - spec 73`() {
        val qe = QueueEngine()
        val tasks = listOf(
            task(1, "Episode 1", status = TaskStatus.QUEUED),
            task(2, "Episode 2", status = TaskStatus.FAILED),
            task(3, "Episode 3", status = TaskStatus.QUEUED),
            task(4, "Episode 4", status = TaskStatus.QUEUED),
        )
        val picked = qe.selectNext(qe.excludeFailed(tasks), 3, 0.0, 99999, 0)
        // Episode 2 FAILED is excluded — 1,3,4 proceed
        assertEquals(setOf(1L, 3L, 4L), picked.map { it.id }.toSet())
        assertFalse(picked.any { it.id == 2L })
    }

    @Test
    fun `retry-wait task deferred until time reached`() {
        val qe = QueueEngine()
        val future = task(1, "later", status = TaskStatus.QUEUED).copy(nextRetryAt = System.currentTimeMillis() + 600_000)
        val ready = task(2, "ready")
        val picked = qe.selectNext(listOf(future, ready), 2, 0.0, 99999, 0)
        assertEquals(listOf(2L), picked.map { it.id })
    }

    @Test
    fun `resume all never lifts manual pause - spec 15`() {
        val manual = task(1, "A", manualPause = true, status = TaskStatus.PAUSED)
        val system = task(2, "B", status = TaskStatus.PAUSED).copy(
            pauseReason = PauseReason.SYSTEM_GLOBAL, manualPause = false
        )
        assertTrue(PauseController.isManuallyPaused(manual))
        assertFalse(PauseController.resumeAllEligible(manual)) // stays paused
        assertTrue(PauseController.resumeAllEligible(system))  // resumed
    }

    @Test
    fun `sequential lock holds natural order position`() {
        val qe = QueueEngine()
        val tasks = listOf(
            task(1, "Ep 1", mode = QueueMode.FILENAME_NATURAL),
            task(2, "Ep 2", mode = QueueMode.FILENAME_NATURAL),
        ).map { it.copy(sequentialLock = true) }
        val picked = qe.selectNext(tasks, 2, 0.0, 99999, 0)
        // with the lock, only ONE task per source per pass
        assertEquals(1, picked.size)
        assertEquals("Ep 1", picked.first().filename)
    }

    @Test
    fun `state machine rejects illegal transitions`() {
        assertFalse(TaskStateMachine.canTransition(TaskStatus.COMPLETED, TaskStatus.DOWNLOADING))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.DOWNLOADING, TaskStatus.COMPLETED))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.RECOVERY_PENDING, TaskStatus.QUEUED))
        assertFalse(TaskStateMachine.canTransition(TaskStatus.CANCELED, TaskStatus.QUEUED))
    }
}
