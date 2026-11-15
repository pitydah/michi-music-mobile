package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs with genuine Android SharedPreferences in Robolectric, not in synthetic QA. */
@RunWith(RobolectricTestRunner::class)
class MusicRootStampTest {
    @Test fun switchingAtoBtoAInvalidatesTheInitialSourceProof() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = MusicRootPreferences(ctx)
        val a = Uri.parse("content://a.example/tree/A")
        val b = Uri.parse("content://b.example/tree/B")
        assertTrue(prefs.setCustomRoot(a, "A", "root-A"))
        val first = prefs.currentRootStamp()
        assertTrue(prefs.setCustomRoot(b, "B", "root-B"))
        assertTrue(prefs.setCustomRoot(a, "A", "root-A"))
        val returned = prefs.currentRootStamp()
        assertEquals("root-A", returned.sourceId)
        assertNotEquals(first, returned)
        assertTrue(returned.epoch > first.epoch)
    }

    @Test fun twoPreferencesInstancesShareTheSameAtomicGeneration() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val a = MusicRootPreferences(ctx)
        val b = MusicRootPreferences(ctx)
        assertTrue(a.restoreDefault())
        assertEquals(a.currentRootStamp(), b.currentRootStamp())
        val before = a.currentRootStamp()
        assertTrue(b.setCustomRoot(Uri.parse("content://source.example/tree/X"), "X", "root-X"))
        assertEquals(a.currentRootStamp(), b.currentRootStamp())
        assertTrue(a.currentRootStamp().epoch > before.epoch)
    }

    @Test fun recordingScanDoesNotAuthorizeNewBindingGeneration() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p = MusicRootPreferences(ctx)
        val before = p.currentRootStamp()
        assertTrue(p.recordScan(trackCount = 50))
        assertEquals(before, p.currentRootStamp())
    }
}
