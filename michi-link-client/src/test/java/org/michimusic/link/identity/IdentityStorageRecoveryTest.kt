package org.michimusic.link.identity

import java.io.IOException
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain-JVM coverage for the retry-once/give-up orchestration behind the MichiIdentity crash
// fix. No Android/Keystore involved by design - see IdentityStorageRecovery's kdoc - so this
// runs without Robolectric.
class IdentityStorageRecoveryTest {

    @Test
    fun validStorage_returnsOnFirstTry_recoveryNeverInvoked() {
        var deleteCalls = 0
        var createCalls = 0

        val result = IdentityStorageRecovery.openWithRecovery(
            deleteCorruptedStorage = { deleteCalls++ },
            createStore = { createCalls++; "store" },
        )

        assertEquals("store", result)
        assertEquals(0, deleteCalls)
        assertEquals(1, createCalls)
    }

    @Test
    fun generalSecurityException_triggersRecovery_thenSucceeds() {
        var deleteCalls = 0
        var createCalls = 0

        val result = IdentityStorageRecovery.openWithRecovery(
            deleteCorruptedStorage = { deleteCalls++ },
            createStore = {
                createCalls++
                if (createCalls == 1) throw GeneralSecurityException("bad tag") else "recovered-store"
            },
        )

        assertEquals("recovered-store", result)
        assertEquals(1, deleteCalls)
        assertEquals(2, createCalls)
    }

    @Test
    fun ioException_triggersRecovery_thenSucceeds() {
        var deleteCalls = 0
        var createCalls = 0

        val result = IdentityStorageRecovery.openWithRecovery(
            deleteCorruptedStorage = { deleteCalls++ },
            createStore = {
                createCalls++
                if (createCalls == 1) throw IOException("corrupted keyset file") else "recovered-store"
            },
        )

        assertEquals("recovered-store", result)
        assertEquals(1, deleteCalls)
        assertEquals(2, createCalls)
    }

    @Test
    fun secondFailureAfterRecovery_propagatesRealError_andDoesNotRetryAgain() {
        var deleteCalls = 0
        var createCalls = 0

        val thrown = try {
            IdentityStorageRecovery.openWithRecovery(
                deleteCorruptedStorage = { deleteCalls++ },
                createStore = {
                    createCalls++
                    throw GeneralSecurityException("still broken, attempt $createCalls")
                },
            )
            null
        } catch (e: GeneralSecurityException) {
            e
        }

        assertTrue(thrown != null)
        assertEquals("still broken, attempt 2", thrown!!.message)
        assertEquals(1, deleteCalls) // recovery attempted exactly once, no retry loop
        assertEquals(2, createCalls) // original attempt + exactly one retry
    }

    @Test
    fun unrelatedError_isNotCaught_recoveryNeverInvoked() {
        var deleteCalls = 0
        var createCalls = 0

        val thrown = try {
            IdentityStorageRecovery.openWithRecovery(
                deleteCorruptedStorage = { deleteCalls++ },
                createStore = { createCalls++; throw IllegalStateException("unrelated bug") },
            )
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(thrown != null)
        assertEquals("unrelated bug", thrown!!.message)
        assertEquals(0, deleteCalls) // an unrelated error must not trigger storage deletion
        assertEquals(1, createCalls)
    }
}
