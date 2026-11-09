package org.michimusic.mobile.library

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.MusicRoot
import org.michimusic.data.repository.MusicRootPreferences
import org.michimusic.data.repository.MusicRootType
import org.michimusic.data.repository.SafMediaRepository
import org.michimusic.data.repository.TreeIndexResult
import org.michimusic.data.repository.customRootSourceId
import org.michimusic.player.AudioController
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MusicRootAuthorityTest {
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    private fun authority(
        prefs: MusicRootPreferences,
        saf: SafMediaRepository,
        local: LocalMediaRepository = mockk(relaxed = true),
        audio: AudioController = mockk(relaxed = true),
    ) = MusicRootAuthority(prefs, saf, local, audio)

    @Test
    fun scanFailureLeavesThePreviousRootAndCatalogUntouched() = runTest {
        val prefs = mockk<MusicRootPreferences>(relaxed = true)
        val saf = mockk<SafMediaRepository>(relaxed = true)
        val audio = mockk<AudioController>(relaxed = true)
        every { prefs.currentRoot() } returns MusicRoot.Default
        coEvery { saf.indexTree(tree, any()) } returns TreeIndexResult.Failed("provider failed")

        val result = authority(prefs, saf, audio = audio)
            .apply(MusicRootSelection.CustomTree(tree))

        assertFalse(result.success)
        assertEquals(MusicRoot.Default, result.root)
        verify(exactly = 0) { prefs.saveRoot(any()) }
        verify(exactly = 0) { audio.clearQueue() }
    }

    @Test
    fun persistenceFailureDropsTheStagedSourceAndKeepsTheOldRoot() = runTest {
        val prefs = mockk<MusicRootPreferences>(relaxed = true)
        val saf = mockk<SafMediaRepository>(relaxed = true)
        val local = mockk<LocalMediaRepository>(relaxed = true)
        val audio = mockk<AudioController>(relaxed = true)
        val sourceId = customRootSourceId(tree)
        every { prefs.currentRoot() } returns MusicRoot.Default
        every { prefs.saveRoot(any()) } returns false
        coEvery { saf.indexTree(tree, any()) } returns TreeIndexResult.Success(tree, sourceId, "Mi música", 1)
        val authority = authority(prefs, saf, local, audio)

        val result = authority.apply(MusicRootSelection.CustomTree(tree))

        assertFalse(result.success)
        assertEquals(MusicRootError.SAVE_FAILED, result.error)
        coVerify { saf.clearSource(sourceId) }
        verify(exactly = 0) { audio.clearQueue() }
    }

    @Test
    fun confirmedCustomRootActivatesNewSourceAndClearsLocalQueue() = runTest {
        val prefs = mockk<MusicRootPreferences>(relaxed = true)
        val saf = mockk<SafMediaRepository>(relaxed = true)
        val local = mockk<LocalMediaRepository>(relaxed = true)
        val audio = mockk<AudioController>(relaxed = true)
        val sourceId = customRootSourceId(tree)
        every { prefs.currentRoot() } returns MusicRoot.Default
        every { prefs.saveRoot(any()) } returns true
        coEvery { saf.indexTree(tree, any()) } returns TreeIndexResult.Success(tree, sourceId, "Mi música", 4)

        val result = authority(prefs, saf, local, audio)
            .apply(MusicRootSelection.CustomTree(tree))

        assertTrue(result.success)
        assertEquals(4, result.trackCount)
        assertEquals(sourceId, result.root.sourceId)
        verify { audio.clearQueue() }
        coVerify { local.invalidateCache() }
    }

    @Test
    fun deferredWizardCommitDoesNotTouchPlaybackUntilActivation() = runTest {
        val prefs = mockk<MusicRootPreferences>(relaxed = true)
        val saf = mockk<SafMediaRepository>(relaxed = true)
        val local = mockk<LocalMediaRepository>(relaxed = true)
        val audio = mockk<AudioController>(relaxed = true)
        val sourceId = customRootSourceId(tree)
        every { prefs.currentRoot() } returns MusicRoot.Default
        every { prefs.saveRoot(any()) } returns true
        coEvery { saf.indexTree(tree, any()) } returns TreeIndexResult.Success(tree, sourceId, "Mi música", 2)
        val authority = authority(prefs, saf, local, audio)

        val result = authority.apply(MusicRootSelection.CustomTree(tree), clearPlayback = false)

        assertTrue(result.success)
        verify(exactly = 0) { audio.clearQueue() }
        authority.activate(result)
        verify(exactly = 1) { audio.clearQueue() }
        coVerify { local.invalidateCache() }
    }

    @Test
    fun failedOnboardingRollbackRestoresPreviousRootAndDropsTheStagedSource() = runTest {
        val oldTree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AOld")
        val oldRoot = MusicRoot(
            type = MusicRootType.CUSTOM_TREE,
            treeUri = oldTree.toString(),
            displayName = "Old",
            sourceId = customRootSourceId(oldTree),
        )
        val newSource = customRootSourceId(tree)
        val prefs = mockk<MusicRootPreferences>(relaxed = true)
        val saf = mockk<SafMediaRepository>(relaxed = true)
        val local = mockk<LocalMediaRepository>(relaxed = true)
        val audio = mockk<AudioController>(relaxed = true)
        every { prefs.currentRoot() } returns oldRoot
        every { prefs.saveRoot(any()) } returns true
        coEvery { saf.indexTree(tree, any()) } returns TreeIndexResult.Success(tree, newSource, "New", 1)
        val authority = authority(prefs, saf, local, audio)

        val change = authority.apply(MusicRootSelection.CustomTree(tree), clearPlayback = false)
        assertTrue(change.success)
        assertTrue(authority.rollback(change))

        verify { prefs.saveRoot(oldRoot) }
        coVerify { saf.clearSource(newSource) }
        verify(exactly = 0) { audio.clearQueue() }
    }

    @Test
    fun rescanMigratesLegacyCustomRootToItsProviderScopedSource() = runTest {
        val legacy = MusicRoot(
            type = MusicRootType.CUSTOM_TREE,
            treeUri = tree.toString(),
            displayName = "Mi música",
            sourceId = "saf_root",
        )
        val newSource = customRootSourceId(tree)
        val prefs = mockk<MusicRootPreferences>(relaxed = true)
        val saf = mockk<SafMediaRepository>(relaxed = true)
        every { prefs.currentRoot() } returns legacy
        every { prefs.saveRoot(any()) } returns true
        coEvery { saf.indexTree(tree, any()) } returns TreeIndexResult.Success(tree, newSource, "Mi música", 3)
        val authority = authority(prefs, saf)

        val result = authority.rescan()

        assertTrue(result.success)
        assertEquals(newSource, result.root.sourceId)
        verify { prefs.saveRoot(legacy.copy(sourceId = newSource)) }
        coVerify { saf.clearSource("saf_root") }
    }

    @Test
    fun rescanningTheSameCustomRootPreservesTheLocalQueueAndPosition() = runTest {
        val source = customRootSourceId(tree)
        val custom = MusicRoot(
            type = MusicRootType.CUSTOM_TREE,
            treeUri = tree.toString(),
            displayName = "Mi música",
            sourceId = source,
        )
        val prefs = mockk<MusicRootPreferences>(relaxed = true)
        val saf = mockk<SafMediaRepository>(relaxed = true)
        val local = mockk<LocalMediaRepository>(relaxed = true)
        val audio = mockk<AudioController>(relaxed = true)
        every { prefs.currentRoot() } returns custom
        coEvery { saf.indexTree(tree, any()) } returns TreeIndexResult.Success(tree, source, "Mi música", 4)
        val authority = authority(prefs, saf, local, audio)

        val result = authority.rescan()

        assertTrue(result.success)
        verify(exactly = 0) { prefs.saveRoot(any()) }
        verify(exactly = 0) { audio.clearQueue() }
        coVerify { local.invalidateCache() }
    }

    @Test
    fun revokedCustomPermissionIsReportedAsRecoverableFailure() = runTest {
        val custom = MusicRoot(
            type = MusicRootType.CUSTOM_TREE,
            treeUri = tree.toString(),
            displayName = "Mi música",
            sourceId = customRootSourceId(tree),
        )
        val prefs = mockk<MusicRootPreferences>(relaxed = true)
        val saf = mockk<SafMediaRepository>(relaxed = true)
        every { prefs.currentRoot() } returns custom
        every { saf.hasPermission(tree) } returns false
        coEvery { saf.indexTree(tree, any()) } returns TreeIndexResult.PermissionDenied
        val authority = authority(prefs, saf)

        assertTrue(authority.permissionRevoked())
        val result = authority.rescan()

        assertFalse(result.success)
        assertEquals(MusicRootError.PERMISSION_DENIED, result.error)
        verify(exactly = 0) { prefs.saveRoot(any()) }
    }
}
