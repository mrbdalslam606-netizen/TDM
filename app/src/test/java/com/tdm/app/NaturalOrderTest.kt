package com.tdm.app

import com.tdm.app.core.queue.NaturalOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Natural filename ordering (spec §13: 1 → 2 → 3 → 10, never 1 → 10 → 2 → 3). */
class NaturalOrderTest {

    @Test
    fun `episodes sort naturally`() {
        val files = listOf("Episode 10.mkv", "Episode 2.mkv", "Episode 1.mkv", "Episode 3.mkv")
        val sorted = NaturalOrder.sortedBy(files) { it }
        assertEquals(
            listOf("Episode 1.mkv", "Episode 2.mkv", "Episode 3.mkv", "Episode 10.mkv"),
            sorted,
        )
    }

    @Test
    fun `multi-digit runs compare numerically`() {
        assertTrue(NaturalOrder.compare("file100.zip", "file2.zip") > 0)
        assertTrue(NaturalOrder.compare("file02.zip", "file2.zip") < 0) // shorter digit-run first on tie
        assertTrue(NaturalOrder.compare("s01e09.mkv", "s01e10.mkv") < 0)
    }

    @Test
    fun `case insensitive text`() {
        assertEquals(0, NaturalOrder.compare("EPISODE 1", "episode 1"))
    }

    @Test
    fun `prefix shorter first`() {
        assertTrue(NaturalOrder.compare("abc", "abc1") < 0)
    }

    @Test
    fun `mixed numbers and text`() {
        val sorted = NaturalOrder.sortedBy(
            listOf("S2E5", "S2E40", "S10E1", "S2E6")
        ) { it }
        assertEquals(listOf("S2E5", "S2E6", "S2E40", "S10E1"), sorted)
    }
}
