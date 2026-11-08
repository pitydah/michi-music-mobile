package org.michimusic.mobile.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class OnboardingStoreTest {
    private fun store(): OnboardingStore = OnboardingStore(
        context = ApplicationProvider.getApplicationContext<Context>(),
        ioDispatcher = Dispatchers.Unconfined,
        fileName = "wizard_test_${System.nanoTime()}",
        installTimes = { 0L to 0L },
    )

    @Test fun freshInstallHasNoCompletionFlag() = runTest {
        val snapshot = store().load()
        assertFalse(snapshot.completed)
        assertEquals(0, snapshot.step)
        assertEquals(LibraryChoice.PHONE, snapshot.library)
    }

    @Test fun draftResumesAndOnlyFinishCompletes() = runTest {
        val store = store()
        val draft = OnboardingSnapshot(step = 3, library = LibraryChoice.IMPORT_FILES,
            discoverLater = true, linkLater = true, audio = AudioChoice.USB_REMINDER)
        assertTrue(store.saveDraft(draft))
        assertEquals(draft, store.load())
        assertFalse(store.load().completed)
        assertTrue(store.complete(draft))
        assertTrue(store.load().completed)
        assertEquals(AudioChoice.USB_REMINDER, store.load().audio)
    }

    @Test fun unknownStoredValueFallsBackSafely() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "wizard_invalid_test_${System.nanoTime()}"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("library_v1", "INVALID").putInt("step_v1", 99).commit()
        val result = OnboardingStore(context, Dispatchers.Unconfined, name,
            installTimes = { 0L to 0L }).load()
        assertEquals(LibraryChoice.PHONE, result.library)
        assertEquals(4, result.step)
    }

    @Test fun corruptStoredTypesDoNotCrashLaunch() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "wizard_corrupt_${System.nanoTime()}"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("completed_v1", "INVALID")
            .putString("step_v1", "INVALID")
            .putInt("audio_v1", -5)
            .putBoolean("library_v1", false).commit()
        val result = OnboardingStore(context, Dispatchers.Unconfined, name,
            installTimes = { 0L to 0L }).load()
        assertFalse(result.completed)
        assertEquals(0, result.step)
        assertEquals(LibraryChoice.PHONE, result.library)
        assertEquals(AudioChoice.ANDROID, result.audio)
    }

    @Test fun upgradedLegacyInstallationKeepsHomeAccessible() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "wizard_upgrade_${System.nanoTime()}"
        val store = OnboardingStore(context, Dispatchers.Unconfined, name,
            installTimes = { 1000L to 301000L })
        assertTrue(store.load().completed)
        assertTrue(store.load().completed) // migration is idempotent
    }

    @Test fun existingDraftIsNeverSilentlyCompletedByUpgrade() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "wizard_draft_upgrade_${System.nanoTime()}"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putInt("step_v1", 2).commit()
        val store = OnboardingStore(context, Dispatchers.Unconfined, name,
            installTimes = { 1000L to 301000L })
        assertFalse(store.load().completed)
        assertEquals(2, store.load().step)
    }

    @Test fun freshInstallWithNearEqualPackageTimesShowsWizard() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "wizard_fresh_${System.nanoTime()}"
        val store = OnboardingStore(context, Dispatchers.Unconfined, name,
            installTimes = { 1000L to 1300L })
        assertFalse(store.load().completed)
    }

    @Test fun replayCompletionDoesNotClearOtherSettings() = runTest {
        val store = store()
        assertTrue(store.complete(OnboardingSnapshot(
            completed = true, library = LibraryChoice.IMPORT_FILES,
            discoverLater = true, linkLater = true, audio = AudioChoice.USB_REMINDER)))
        assertTrue(store.load().completed)
        assertTrue(store.complete(OnboardingSnapshot(
            completed = true, library = LibraryChoice.LATER,
            discoverLater = false, linkLater = false, audio = AudioChoice.ANDROID)))
        assertEquals(LibraryChoice.LATER, store.load().library)
        assertFalse(store.load().discoverLater)
        assertFalse(store.load().linkLater)
    }
}
