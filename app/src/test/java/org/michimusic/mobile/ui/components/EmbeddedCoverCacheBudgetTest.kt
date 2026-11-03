package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-04: the embedded-cover cache was a flat 16 MiB, which is most of the heap on a
 * low-RAM phone and needlessly tight on a flagship. The budget now follows the memory
 * class, bounded on both sides.
 */
class EmbeddedCoverCacheBudgetTest {

    @Test
    fun `a low ram device gets a smaller share than a flagship`() {
        val lowRam = embeddedCoverCacheBudgetKb(memoryClassMb = 128, isLowRamDevice = true)
        val flagship = embeddedCoverCacheBudgetKb(memoryClassMb = 512, isLowRamDevice = false)
        assertTrue("low-RAM budget should be smaller", lowRam < flagship)
    }

    @Test
    fun `the budget never drops below the floor`() {
        assertEquals(4 * 1024, embeddedCoverCacheBudgetKb(memoryClassMb = 16, isLowRamDevice = true))
    }

    @Test
    fun `the budget never exceeds the ceiling`() {
        assertEquals(16 * 1024, embeddedCoverCacheBudgetKb(memoryClassMb = 4096, isLowRamDevice = false))
    }

    @Test
    fun `a typical phone lands between the bounds`() {
        val typical = embeddedCoverCacheBudgetKb(memoryClassMb = 192, isLowRamDevice = false)
        assertTrue(typical in (4 * 1024)..(16 * 1024))
    }

    @Test
    fun `budget is monotonic in the memory class`() {
        var previous = 0
        for (memoryClass in listOf(64, 96, 128, 192, 256, 384, 512)) {
            val budget = embeddedCoverCacheBudgetKb(memoryClass, isLowRamDevice = false)
            assertTrue("budget should not shrink as memory grows", budget >= previous)
            previous = budget
        }
    }
}
