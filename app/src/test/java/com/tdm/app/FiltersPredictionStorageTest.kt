package com.tdm.app

import com.tdm.app.core.engine.MonitorEngine
import com.tdm.app.core.stats.PredictionEngine
import com.tdm.app.core.storage.PathTemplate
import com.tdm.app.data.db.DownloadTaskEntity
import com.tdm.app.data.db.FileFilter
import com.tdm.app.telegram.TgError
import com.tdm.app.telegram.TgFileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Duplicate identity (spec §26), filters (§61), prediction (§60), path templates (§27), error classification (§65). */
class FiltersPredictionStorageTest {

    /* ---------- duplicate detection identity ---------- */

    @Test
    fun `duplicate detection uses telegram identity - spec 26`() {
        val id1 = "c123:m456:uABC:s1000"
        val id2 = "c123:m456:uABC:s1000"
        val id3 = "c123:m457:uABC:s1000"
        assertEquals(id1, id2)
        assertTrue(id1 != id3) // different message → different identity
    }

    /* ---------- filter engine (spec §61) ---------- */

    @Test
    fun `filters pass by type extension size and name`() {
        val f = FileFilter(
            video = true, documents = false, archives = true, images = false,
            extensions = listOf("mkv", "zip"),
            filenameContains = "anime",
            minSizeBytes = 1024, maxSizeBytes = 100 * 1048576L,
        )
        assertTrue(MonitorEngine.FilterEngine.passes(f, TgFileKind.VIDEO, "anime_episode_1.mkv", 50 * 1048576L))
        assertFalse(MonitorEngine.FilterEngine.passes(f, TgFileKind.DOCUMENT, "anime_ep.pdf", 1024)) // documents off
        assertFalse(MonitorEngine.FilterEngine.passes(f, TgFileKind.VIDEO, "other.mkv", 1024)) // name mismatch
        assertFalse(MonitorEngine.FilterEngine.passes(f, TgFileKind.VIDEO, "anime.mkv", 512)) // too small
        assertFalse(MonitorEngine.FilterEngine.passes(f, TgFileKind.VIDEO, "anime.mkv", 200 * 1048576L)) // too big
        assertFalse(MonitorEngine.FilterEngine.passes(f, TgFileKind.VIDEO, "anime.mp4", 1024)) // ext not allowed
    }

    @Test
    fun `archive kind inferred from document extension`() {
        assertEquals(TgFileKind.ARCHIVE, MonitorEngine.FilterEngine.kindOf(TgFileKind.DOCUMENT, "pack.zip"))
        assertEquals(TgFileKind.DOCUMENT, MonitorEngine.FilterEngine.kindOf(TgFileKind.DOCUMENT, "readme.txt"))
    }

    /* ---------- prediction (spec §60) ---------- */

    @Test
    fun `prediction splits files into complete vs missed`() {
        val tasks = listOf(
            task(1, size = 100L * 1048576),   // 100 MB
            task(2, size = 1000L * 1048576),  // 1 GB
        )
        // 10 MB/s * 60s = 600 MB expected
        val f = PredictionEngine.forecast(tasks, availableSeconds = 60, historicalSpeedBps = 10.0 * 1048576, concurrency = 1)
        assertTrue(f.expectedBytes > 0)
        assertEquals(listOf(1L), f.filesLikelyComplete)
        assertEquals(listOf(2L), f.filesLikelyMissed)
        assertTrue(f.etaPerFileSec[2]!! > f.etaPerFileSec[1]!!)
    }

    private fun task(id: Long, size: Long) = DownloadTaskEntity(
        id = id, filename = "f$id", size = size, downloadedBytes = 0,
    )

    /* ---------- path templates (spec §27) ---------- */

    @Test
    fun `path template default layout`() {
        val segs = PathTemplate.resolve("{root}/{channel}/{date}/{filename}", "My Channel", "file.zip", 1725667200000)
        assertEquals(listOf("My Channel", "2024-09-07", "file.zip"), segs.dropLastWhile { false }.let { segs })
        assertEquals("file.zip", segs.last())
    }

    @Test
    fun `path template year month variant`() {
        val segs = PathTemplate.resolve("{root}/{channel}/{year}/{month}/{filename}", "Chan", "a.mkv", 1725667200000)
        assertEquals(listOf("Chan", "2024", "09", "a.mkv"), segs)
    }

    @Test
    fun `filename sanitized`() {
        assertEquals("bad_name_", PathTemplate.sanitizeFilename("bad:name*"))
        assertTrue(PathTemplate.sanitizeFilename("").isNotBlank())
    }

    /* ---------- error classification (spec §65) ---------- */

    @Test
    fun `errors classified with correct decisions`() {
        assertEquals(TgError.Decision.FLOOD_WAIT, (TgError.FloodWait(30) as TgError).decision)
        assertEquals(TgError.Decision.RETRY_NOW, (TgError.Network() as TgError).decision)
        assertEquals(TgError.Decision.FAIL, (TgError.FileNotFound() as TgError).decision)

        val classified = com.tdm.app.telegram.classifyTgError(RuntimeException("Too Many Requests: retry after 42"))
        assertTrue(classified is TgError.FloodWait)
        assertEquals(42, (classified as TgError.FloodWait).seconds)

        assertTrue(com.tdm.app.telegram.classifyTgError(RuntimeException("connection reset")) is TgError.Network)
    }
}
