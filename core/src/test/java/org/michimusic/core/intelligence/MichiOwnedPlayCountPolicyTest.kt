package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiOwnedPlayCountPolicyTest {
    private val policy = MichiOwnedPlayCountPolicy

    @Test fun missingOwnerCountIsUnknownInsteadOfZero() {
        assertNull(policy.decode(""))
        assertNull(policy.decode("000000020000|0"))
        assertNull(policy.decode("000000020000|-1"))
    }

    @Test fun completedCountIsSaturatedAndLastDayNeverGoesBackwards() {
        var count: MichiOwnedPlayCountPolicy.Count? = null
        repeat(300) { count = policy.addCompleted(count, 20_000L) }
        assertEquals(policy.MAX_COUNT, count?.completed)
        assertEquals(20_000L, count?.lastCompletedEpochDay)
        count = policy.addCompleted(count, 19_999L)
        assertEquals(20_000L, count?.lastCompletedEpochDay)
        count = policy.addCompleted(count, 20_001L)
        assertEquals(20_001L, count?.lastCompletedEpochDay)
    }

    @Test fun encodedPositiveCounterCanRoundTripWithoutRawIdentity() {
        val count = MichiOwnedPlayCountPolicy.Count(2, 20_000)
        val encoded = policy.encode(count)
        assertEquals(count, policy.decode(encoded))
        assertTrue(!encoded.contains("track"))
        assertNull(policy.decode("000000020000|256"))
        assertNull(policy.decode("000000020000|garbage"))
    }

    @Test fun invalidEpochCannotCreateCounter() {
        assertNull(policy.addCompleted(null, -1))
        assertNull(policy.addCompleted(null, policy.MAX_EPOCH_DAY + 1))
    }
}
