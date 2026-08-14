package org.michimusic.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class MichiAudioEffectsTest {

    private lateinit var mockContext: Context
    private val prefMap = mutableMapOf<String, Any>()

    @Before
    fun setup() {
        prefMap.clear()
        val fakePrefs = createFakeSharedPreferences(prefMap)
        mockContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun initialState_hasDefaultFallbackBands() {
        val audioEffects = MichiAudioEffects(mockContext)
        val state = audioEffects.effectsState.value

        assertTrue(state.isEnabled)
        assertEquals("Michi Signature", state.selectedPreset)
        assertEquals(5, state.bands.size)
        assertEquals(60, state.bands[0].centerFreqHz)
        assertEquals(14000, state.bands[4].centerFreqHz)
    }

    @Test
    fun setEnabled_updatesStateAndPersists() {
        val audioEffects = MichiAudioEffects(mockContext)
        audioEffects.setEnabled(false)

        assertFalse(audioEffects.effectsState.value.isEnabled)
        assertEquals(false, prefMap["eq_enabled"])

        audioEffects.setEnabled(true)
        assertTrue(audioEffects.effectsState.value.isEnabled)
        assertEquals(true, prefMap["eq_enabled"])
    }

    @Test
    fun setBandGain_updatesSpecificBand() {
        val audioEffects = MichiAudioEffects(mockContext)
        audioEffects.setBandGain(0, 450)

        val updatedBand = audioEffects.effectsState.value.bands.find { it.index == 0 }
        assertNotNull(updatedBand)
        assertEquals(450, updatedBand?.currentGainMilliDb)
        assertEquals("Personalizado", audioEffects.effectsState.value.selectedPreset)
        assertEquals(450, prefMap["band_0"])
    }

    @Test
    fun applyPreset_updatesAllBandsCorrectly() {
        val audioEffects = MichiAudioEffects(mockContext)
        audioEffects.applyPreset("Bass Punch")

        val state = audioEffects.effectsState.value
        assertEquals("Bass Punch", state.selectedPreset)
        assertEquals(700, state.bands[0].currentGainMilliDb)
        assertEquals(450, state.bands[1].currentGainMilliDb)
        assertEquals("Bass Punch", prefMap["selected_preset"])
    }

    @Test
    fun resetToFlat_setsAllBandsToZero() {
        val audioEffects = MichiAudioEffects(mockContext)
        audioEffects.applyPreset("Bass Punch")
        audioEffects.setPreamp(4.5f)

        audioEffects.resetToFlat()
        val state = audioEffects.effectsState.value
        assertEquals("Flat / Plano", state.selectedPreset)
        assertEquals(0f, state.preampGainDb)
        assertTrue(state.bands.all { it.currentGainMilliDb == 0 })
    }

    @Test
    fun setPreamp_boundsAndPersists() {
        val audioEffects = MichiAudioEffects(mockContext)
        audioEffects.setPreamp(6.0f)

        assertEquals(6.0f, audioEffects.effectsState.value.preampGainDb)
        assertEquals(6.0f, prefMap["preamp_gain"])
    }

    @Test
    fun setBassBoostStrength_boundsAndPersists() {
        val audioEffects = MichiAudioEffects(mockContext)
        audioEffects.setBassBoost(800)

        assertEquals(800, audioEffects.effectsState.value.bassBoostStrength)
        assertEquals(800, prefMap["bass_strength"])
    }

    @Test
    fun setVirtualizerStrength_boundsAndPersists() {
        val audioEffects = MichiAudioEffects(mockContext)
        audioEffects.setVirtualizer(550)

        assertEquals(550, audioEffects.effectsState.value.virtualizerStrength)
        assertEquals(550, prefMap["virtualizer_strength"])
    }

    @Test
    fun setLoudness_boundsAndPersists() {
        val audioEffects = MichiAudioEffects(mockContext)
        audioEffects.setLoudness(400)

        assertEquals(400, audioEffects.effectsState.value.loudnessGainMb)
        assertEquals(400, prefMap["loudness_gain"])
    }

    private fun createFakeSharedPreferences(store: MutableMap<String, Any>): SharedPreferences {
        lateinit var editorProxy: SharedPreferences.Editor
        editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putBoolean" -> {
                    store[args[0] as String] = args[1] as Boolean
                    editorProxy
                }
                "putInt" -> {
                    store[args[0] as String] = args[1] as Int
                    editorProxy
                }
                "putFloat" -> {
                    store[args[0] as String] = args[1] as Float
                    editorProxy
                }
                "putString" -> {
                    store[args[0] as String] = args[1] as String
                    editorProxy
                }
                "apply", "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> store[args[0] as String] as? Boolean ?: (args.getOrNull(1) as? Boolean ?: false)
                "getInt" -> store[args[0] as String] as? Int ?: (args.getOrNull(1) as? Int ?: 0)
                "getFloat" -> store[args[0] as String] as? Float ?: (args.getOrNull(1) as? Float ?: 0f)
                "getString" -> store[args[0] as String] as? String ?: (args.getOrNull(1) as? String)
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences
    }
}
