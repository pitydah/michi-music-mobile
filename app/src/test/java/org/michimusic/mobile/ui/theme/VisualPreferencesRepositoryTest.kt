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
}
