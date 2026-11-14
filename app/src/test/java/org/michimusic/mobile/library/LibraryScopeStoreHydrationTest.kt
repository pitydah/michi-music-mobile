package org.michimusic.mobile.library

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.MusicLibrary
import org.michimusic.data.cache.AppDao
import org.michimusic.data.repository.MusicLibraryRepository

class LibraryScopeStoreHydrationTest {
    @Test fun storedOneDoesNotExposeTemporaryAllAsAuthorized() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val dao = mockk<AppDao>(relaxed = true)
        coEvery { dao.getSetting(LibraryScopeStore.KEY_ACTIVE_SCOPE) } coAnswers {
            gate.await(); "lib_saved"
        }
        val lib = mockk<MusicLibrary>()
        every { lib.id } returns "lib_saved"
        val repo = mockk<MusicLibraryRepository>()
        every { repo.observeLibraries() } returns flowOf(listOf(lib))
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = LibraryScopeStore(dao, repo, work)
            assertFalse(store.hydrated.value)
            gate.complete(Unit)
            withTimeout(2_000) { store.hydrated.first { it } }
            assertEquals(LibraryScope.One("lib_saved"), store.activeScope.value)
        } finally { work.cancel() }
    }

    @Test fun lateRestoreCannotOverwriteExplicitSelection() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val dao = mockk<AppDao>(relaxed = true)
        coEvery { dao.getSetting(LibraryScopeStore.KEY_ACTIVE_SCOPE) } coAnswers {
            gate.await(); "lib_old"
        }
        val repo = mockk<MusicLibraryRepository>(relaxed = true)
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = LibraryScopeStore(dao, repo, work)
            store.setScope(LibraryScope.One("lib_new"))
            gate.complete(Unit)
            coVerify(timeout = 2_000) { dao.setSetting(match { it.value == "lib_new" }) }
            assertEquals(LibraryScope.One("lib_new"), store.activeScope.value)
            assertEquals(true, store.hydrated.value)
        } finally { work.cancel() }
    }
}
