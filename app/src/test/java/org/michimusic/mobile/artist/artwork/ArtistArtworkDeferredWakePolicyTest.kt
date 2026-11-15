package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistArtworkDeferredWakePolicyTest {
    private val hour = ArtistArtworkDeferredWakePolicy.WINDOW_MS

    @Test fun pendingWithinAnHourIsCoalescedToSameWake() {
        val now = hour * 100 + 10L
        val a = ArtistArtworkDeferredWakePolicy.wakeTime(now, hour * 100 + 15L)
        val b = ArtistArtworkDeferredWakePolicy.wakeTime(now, hour * 100 + 360L)
        assertEquals(hour * 101, a)
        assertEquals(a, b)
    }

    @Test fun exactHourDeadlineHasNoUnnecessaryExtraHour() {
        assertEquals(hour * 102, ArtistArtworkDeferredWakePolicy.wakeTime(hour * 100, hour * 102))
    }

    @Test fun alreadyDueInvalidAndExcessivelyDistantDeadlinesNeverSchedule() {
        val now = hour * 100
        assertNull(ArtistArtworkDeferredWakePolicy.wakeTime(now, now))
        assertNull(ArtistArtworkDeferredWakePolicy.wakeTime(now, now - 1))
        assertNull(ArtistArtworkDeferredWakePolicy.wakeTime(-1, now))
        assertNull(ArtistArtworkDeferredWakePolicy.wakeTime(now, now + 9L * 24 * hour))
        assertNull(ArtistArtworkDeferredWakePolicy.wakeTime(Long.MAX_VALUE - 4, Long.MAX_VALUE - 1))
    }

    @Test fun sourceAndLibraryAreIsolatedWithoutLeakingRootStrings() {
        val name1 = ArtistArtworkDeferredWakePolicy.uniqueWorkName("lib", "content://tree/private1", hour * 200)
        val name2 = ArtistArtworkDeferredWakePolicy.uniqueWorkName("lib", "content://tree/private2", hour * 200)
        val name3 = ArtistArtworkDeferredWakePolicy.uniqueWorkName("other-lib", "content://tree/private1", hour * 200)
        assertNotEquals(name1, name2)
        assertNotEquals(name1, name3)
        assertTrue(name1!!.startsWith("michi.artist.artwork.deferred."))
        assertTrue(!name1.contains("private1"))
        assertTrue(!name1.contains("content:"))
    }

    @Test fun stableKeyAndMalformedKeyRejection() {
        val first = ArtistArtworkDeferredWakePolicy.uniqueWorkName("A", "B:C", hour * 500)
        val again = ArtistArtworkDeferredWakePolicy.uniqueWorkName("A", "B:C", hour * 500)
        assertEquals(first, again)
        assertNotEquals(first, ArtistArtworkDeferredWakePolicy.uniqueWorkName("A:B", "C", hour * 500))
        assertNull(ArtistArtworkDeferredWakePolicy.uniqueWorkName("A", "B", hour * 500 + 1))
        assertNull(ArtistArtworkDeferredWakePolicy.sourceFingerprint("", "B"))
        assertNull(ArtistArtworkDeferredWakePolicy.sourceFingerprint("A", ""))
    }
}
