package org.michimusic.mobile.ui.theme

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The repository used to read SharedPreferences in its constructor, which StrictMode
 * reported as a DiskRead on the main thread every time MichiTheme was mounted. These
 * tests pin the new contract: construction publishes deterministic defaults, and the
 * stored values only arrive once [VisualPreferencesRepository.start] loads them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class VisualPreferencesRepositoryTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun prefs() = context.getSharedPreferences("michi_settings", 0)

    @Before
    fun resetStorage() {
        prefs().edit().clear().commit()
    }

    @Test
    fun constructionPublishesDefaultsWithoutReadingStorage() {
        prefs().edit().putBoolean("high_contrast", true).commit()

        val repository = VisualPreferencesRepository(context, UnconfinedTestDispatcher())

        assertEquals(VisualPreferences(), repository.preferences.value)
        assertFalse(repository.ready.value)
    }

    @Test
    fun startPublishesTheStoredValuesAndMarksTheThemeReady() = runTest {
        prefs().edit()
            .putBoolean("high_contrast", true)
            .putBoolean("reduced_motion", true)
            .putBoolean("haptics_enabled", false)
            .commit()
        val repository = VisualPreferencesRepository(context, UnconfinedTestDispatcher(testScheduler))

        repository.start(this)
        advanceUntilIdle()

        assertTrue(repository.ready.value)
        assertEquals(
            VisualPreferences(highContrast = true, reducedMotion = true, hapticsEnabled = false),
            repository.preferences.value,
        )
        repository.stop()
    }

    @Test
    fun settersPublishImmediatelyAndPersist() = runTest {
        val repository = VisualPreferencesRepository(context, UnconfinedTestDispatcher(testScheduler))
        repository.start(this)
        advanceUntilIdle()

        repository.setHighContrast(true)
        repository.setReducedMotion(true)
        repository.setHapticsEnabled(false)

        val published = repository.preferences.value
        assertTrue(published.highContrast)
        assertTrue(published.reducedMotion)
        assertFalse(published.hapticsEnabled)
        assertTrue(prefs().getBoolean("high_contrast", false))
        assertTrue(prefs().getBoolean("reduced_motion", false))
        assertFalse(prefs().getBoolean("haptics_enabled", true))
        repository.stop()
    }

    @Test
    fun startIsIdempotent() = runTest {
        val repository = VisualPreferencesRepository(context, UnconfinedTestDispatcher(testScheduler))

        repository.start(this)
        repository.start(this)
        advanceUntilIdle()

        assertTrue(repository.ready.value)
        assertEquals(VisualPreferences(), repository.preferences.value)
        repository.stop()
    }

    @Test
    fun appearanceUsesSameStoreAndRestoresAfterRestart() = runTest {
        val repo = VisualPreferencesRepository(context, UnconfinedTestDispatcher(testScheduler))
        repo.start(this)
        advanceUntilIdle()
        repo.setAppearanceProfile(AeroAppearanceProfile.DISCRETO)
        repo.setMicrotexturesEnabled(false)
        repo.setSeekerStyle(AeroSeekerStyle.WAVY)
        repo.setCoverFlowReflection(AeroCoverFlowReflection.INTENSO)
        assertEquals(AeroAppearanceProfile.DISCRETO, repo.preferences.value.appearanceProfile)
        assertFalse(repo.preferences.value.microtexturesEnabled)
        assertEquals(AeroSeekerStyle.WAVY, repo.preferences.value.seekerStyle)
        assertEquals(AeroCoverFlowReflection.INTENSO, repo.preferences.value.coverFlowReflection)

        val restarted = VisualPreferencesRepository(context, UnconfinedTestDispatcher(testScheduler))
        restarted.start(this)
        advanceUntilIdle()
        assertEquals(AeroAppearanceProfile.DISCRETO, restarted.preferences.value.appearanceProfile)
        assertFalse(restarted.preferences.value.microtexturesEnabled)
        assertEquals(AeroSeekerStyle.WAVY, restarted.preferences.value.seekerStyle)
        assertEquals(AeroCoverFlowReflection.INTENSO, restarted.preferences.value.coverFlowReflection)
        restarted.resetAppearance()
        assertEquals(VisualPreferences(), restarted.preferences.value)
        assertEquals(AeroAppearanceProfile.EQUILIBRADO.name,
            prefs().getString("aero_appearance_profile", null))
        assertTrue(prefs().getBoolean("aero_microtextures_enabled", false))
        assertEquals(AeroSeekerStyle.ORIGINAL.name, prefs().getString("aero_seeker_style", null))
        assertEquals(AeroCoverFlowReflection.NORMAL.name,
            prefs().getString("aero_coverflow_reflection", null))
        restarted.stop()
        repo.stop()
    }

    @Test
    fun unknownOrCorruptAppearanceValuesDoNotInvalidateOtherPreferences() = runTest {
        prefs().edit().putBoolean("high_contrast", true)
            .putString("aero_appearance_profile", "UNKNOWN_FUTURE_VALUE")
            .putString("aero_microtextures_enabled", "wrong_type")
            .putString("aero_coverflow_reflection", "UNKNOWN_FUTURE_MODE")
            .commit()
        val repo = VisualPreferencesRepository(context, UnconfinedTestDispatcher(testScheduler))
        repo.start(this)
        advanceUntilIdle()
        assertTrue(repo.preferences.value.highContrast)
        assertEquals(AeroAppearanceProfile.EQUILIBRADO, repo.preferences.value.appearanceProfile)
        assertTrue(repo.preferences.value.microtexturesEnabled)
        assertEquals(AeroCoverFlowReflection.NORMAL, repo.preferences.value.coverFlowReflection)
        repo.stop()
    }
}
