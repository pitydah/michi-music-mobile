package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiMixQueuePolicyTest {
    private fun key(i: Int) = MichiIntelligencePolicy.TrackKey("local", "$i")
    @Test fun selectedTrackStartsAtItsOwnPositionWithoutFullLibrary() {
        val keys = (1..6).map(::key)
        val result = MichiMixQueuePolicy.plan(keys, key(4))!!
        assertEquals(3, result.startIndex)
        assertEquals(keys, result.keys)
    }
    @Test fun playAllStartsAtFirstMixTrack() {
        assertEquals(0, MichiMixQueuePolicy.plan(listOf(key(2), key(1)))?.startIndex)
    }
    @Test fun duplicatedUnknownOrOversizedMixFailsClosed() {
        assertNull(MichiMixQueuePolicy.plan(listOf(key(1), key(1))))
        assertNull(MichiMixQueuePolicy.plan((0..6).map(::key)))
        assertNull(MichiMixQueuePolicy.plan(listOf(key(1)), key(2)))
        assertNull(MichiMixQueuePolicy.plan(emptyList()))
        assertNull(MichiMixQueuePolicy.plan(listOf(MichiIntelligencePolicy.TrackKey("", ""))))
        assertEquals(0, MichiMixQueuePolicy.plan(listOf(MichiIntelligencePolicy.TrackKey("", "1")))?.startIndex)
    }
    @Test fun inputIsCopiedToPreventMutationOfQueuedKeys() {
        val src = mutableListOf(key(1), key(2))
        val result = MichiMixQueuePolicy.plan(src)!!
        src.clear()
        assertEquals(2, result.keys.size)
    }
}
