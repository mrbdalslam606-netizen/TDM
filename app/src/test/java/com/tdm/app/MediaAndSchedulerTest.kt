package com.tdm.app

import com.tdm.app.core.engine.MonitorEngine
import com.tdm.app.core.scheduler.ScheduleMatcher
import com.tdm.app.data.db.FileFilter
import com.tdm.app.telegram.TgFileKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MediaAndSchedulerTest {
    @Test
    fun `default media filter accepts audio images and documents`() {
        val filter = FileFilter()
        assertTrue(MonitorEngine.FilterEngine.passes(filter, TgFileKind.AUDIO, "track.mp3", 10))
        assertTrue(MonitorEngine.FilterEngine.passes(filter, TgFileKind.IMAGE, "photo.jpg", 10))
        assertTrue(MonitorEngine.FilterEngine.passes(filter, TgFileKind.DOCUMENT, "file.pdf", 10))
    }

    @Test
    fun `overnight window follows start day after midnight`() {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val window = ScheduleMatcher.WindowRef(ScheduleMatcher.ALL_DAYS, 23 * 60, 2 * 60)
        assertTrue(ScheduleMatcher.isActive(listOf(window), c.timeInMillis))
        assertTrue(ScheduleMatcher.remainingSeconds(listOf(window), c.timeInMillis) > 0)
    }

    @Test
    fun `same start and end is not accidentally an always active window`() {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val window = ScheduleMatcher.WindowRef(ScheduleMatcher.ALL_DAYS, 12 * 60, 12 * 60)
        assertFalse(ScheduleMatcher.isActive(listOf(window), c.timeInMillis))
    }
}
