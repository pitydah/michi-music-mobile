package org.michimusic.mobile.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        repository = VisualPreferencesRepository(context)
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
    fun testExternalSharedPreferencesChangeTriggersStateFlow() = runTest {
        val prefs = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("high_contrast", true).putBoolean("reduced_motion", true).commit()

        assertTrue(repository.preferences.value.highContrast)
        assertTrue(repository.preferences.value.reducedMotion)
    }
}
