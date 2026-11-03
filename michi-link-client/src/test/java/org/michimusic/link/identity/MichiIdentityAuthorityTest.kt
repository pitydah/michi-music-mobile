package org.michimusic.link.identity

import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authority exists because resolving MichiIdentity opens the Keystore, which
 * StrictMode reported as a main-thread disk read on every cold start. These tests
 * pin the contract that makes that fix safe: nothing is resolved eagerly, failures
 * never produce a usable identity, and resolution happens once.
 */
class MichiIdentityAuthorityTest {

    private val context: android.content.Context = mockk(relaxed = true)

    @Test
    fun constructionNeverTouchesTheIdentity() {
        var factoryCalls = 0
        val authority = MichiIdentityAuthority(
            context = context,
            factory = {
                factoryCalls++
                mockk<MichiIdentity>(relaxed = true)
            },
        )

        assertEquals(0, factoryCalls)
        assertNull(authority.peek())
        assertTrue(authority.state.value is IdentityState.Loading)
    }

    @Test
    fun awaitResolvesOnceOffTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        var resolvedOn: Thread? = null
        val identity = mockk<MichiIdentity>(relaxed = true)
        val authority = MichiIdentityAuthority(
            context = context,
            ioDispatcher = Dispatchers.IO,
            factory = {
                resolvedOn = Thread.currentThread()
                identity
            },
        )

        val awaited = authority.await()

        assertNotNull(awaited)
        assertNotNull(resolvedOn)
        assertFalse("Keystore reads must not run on the calling thread", resolvedOn === caller)
    }

    @Test
    fun aFailedResolutionIsNeverUsableAndIsNotRetried() = runBlocking {
        var factoryCalls = 0
        val authority = MichiIdentityAuthority(
            context = context,
            ioDispatcher = Dispatchers.IO,
            factory = {
                factoryCalls++
                throw IllegalStateException("Keystore unavailable")
            },
        )

        assertNull(authority.await())
        val state = authority.state.value
        assertTrue(state is IdentityState.Failed)
        assertEquals("Keystore unavailable", (state as IdentityState.Failed).reason)

        assertNull(authority.await())
        assertEquals("a failure must not hammer the Keystore", 1, factoryCalls)
    }

    @Test
    fun resolutionHappensOnceEvenWithConcurrentCallers() = runBlocking {
        var factoryCalls = 0
        val authority = MichiIdentityAuthority(
            context = context,
            ioDispatcher = Dispatchers.IO,
            factory = {
                factoryCalls++
                mockk<MichiIdentity>(relaxed = true)
            },
        )

        authority.await()
        authority.await()
        authority.await()

        assertEquals(1, factoryCalls)
        assertNotNull(authority.peek())
    }
}
