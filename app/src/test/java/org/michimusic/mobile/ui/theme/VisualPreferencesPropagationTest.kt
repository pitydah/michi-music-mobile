package org.michimusic.mobile.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class VisualPreferencesPropagationTest {

    private lateinit var context: Context
    private lateinit var repository: VisualPreferencesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE).edit().clear().commit()
        // A controlled dispatcher keeps start() deterministic: with the real IO
        // dispatcher the load would outlive advanceUntilIdle().
        repository = VisualPreferencesRepository(context, kotlinx.coroutines.test.UnconfinedTestDispatcher())
    }

    @Test
    fun testDefaultVisualPreferencesAreDisabled() {
        val initial = repository.preferences.value
        assertFalse("High contrast debe estar deshabilitado por defecto", initial.highContrast)
        assertFalse("Reduced motion debe estar deshabilitado por defecto", initial.reducedMotion)
    }

    @Test
    fun testSetHighContrastUpdatesStateFlowAndPrefs() = runTest {
        repository.setHighContrast(true)

        assertTrue(repository.preferences.value.highContrast)
        val prefs = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("high_contrast", false))

        repository.setHighContrast(false)
        assertFalse(repository.preferences.value.highContrast)
        assertFalse(prefs.getBoolean("high_contrast", false))
    }

    @Test
    fun testSetReducedMotionUpdatesStateFlowAndPrefs() = runTest {
        repository.setReducedMotion(true)

        assertTrue(repository.preferences.value.reducedMotion)
        val prefs = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("reduced_motion", false))

        repository.setReducedMotion(false)
        assertFalse(repository.preferences.value.reducedMotion)
        assertFalse(prefs.getBoolean("reduced_motion", false))
    }

    @Test
    fun testSetHapticsEnabledUpdatesStateFlowAndPrefs() = runTest {
        assertTrue(repository.preferences.value.hapticsEnabled)

        repository.setHapticsEnabled(false)
        assertFalse(repository.preferences.value.hapticsEnabled)
        val prefs = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("haptics_enabled", true))

        repository.setHapticsEnabled(true)
        assertTrue(repository.preferences.value.hapticsEnabled)
        assertTrue(prefs.getBoolean("haptics_enabled", true))
    }

    @Test
    fun testExternalSharedPreferencesChangeIsPickedUpOnStart() = runTest {
        // The constructor no longer reads disk, so a value written by another component
        // (Settings, another entry point) is picked up by the first start() load. The
        // post-start SharedPreferences listener needs a real looper, so it is verified
        // on device instead of being asserted through Robolectric's shadow.
        val prefs = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("high_contrast", true)
            .putBoolean("reduced_motion", true)
            .putBoolean("haptics_enabled", false)
            .commit()

        repository.start(this)
        advanceUntilIdle()

        assertTrue(repository.preferences.value.highContrast)
        assertTrue(repository.preferences.value.reducedMotion)
        assertFalse(repository.preferences.value.hapticsEnabled)
        assertTrue(repository.ready.value)
        repository.stop()
    }
}
