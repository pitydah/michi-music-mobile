package org.michimusic.mobile.ui.components

import org.junit.Assert.*
import org.junit.Test

class SyncProgressPresentationTest {
    @Test fun `unknown total is genuinely indeterminate`() {
        assertNull(syncProgressPresentation(5, 0).fraction)
        assertTrue(syncProgressPresentation(5, 0).detail.contains("total pendiente"))
    }
    @Test fun `zero completed is zero not invented five percent`() {
        assertEquals(0f, syncProgressPresentation(0, 20).fraction!!, 0.001f)
    }
    @Test fun `known total is bounded`() {
        assertEquals(1f, syncProgressPresentation(30, 20).fraction!!, 0.001f)
        assertEquals(0f, syncProgressPresentation(-1, 20).fraction!!, 0.001f)
    }
}
