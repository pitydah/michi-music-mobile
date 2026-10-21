package org.michimusic.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.AudioProcessingMode
import org.michimusic.core.models.BitPerfectStatus
import org.michimusic.core.models.PcmFormat
import java.lang.reflect.Proxy

class AudioModeAndSignalTruthTest {

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

    @Test
    fun transparentModeBypassesEffectsTest() {
        val audioEffects = MichiAudioEffects(mockContext)
        val replayGain = ReplayGainAudioProcessor()
        val modeManager = AudioModeManager(mockContext, audioEffects, replayGain)

        // Inicialmente en DSP
        modeManager.setMode(AudioProcessingMode.DSP)
        assertFalse(audioEffects.isBypassedByMode)
        assertFalse(replayGain.isBypassed)

        // Cambiar a TRANSPARENT
        modeManager.setMode(AudioProcessingMode.TRANSPARENT)
        assertTrue(audioEffects.isBypassedByMode)
        assertTrue(replayGain.isBypassed)
    }

    @Test
    fun dspModeRestoresEffectsTest() {
        val audioEffects = MichiAudioEffects(mockContext)
        val replayGain = ReplayGainAudioProcessor()
        val modeManager = AudioModeManager(mockContext, audioEffects, replayGain)

        modeManager.setMode(AudioProcessingMode.TRANSPARENT)
        assertTrue(audioEffects.isBypassedByMode)
        assertTrue(replayGain.isBypassed)

        // Restaurar a DSP
        modeManager.setMode(AudioProcessingMode.DSP)
        assertFalse(audioEffects.isBypassedByMode)
        assertFalse(replayGain.isBypassed)
    }

    @Test
    fun signalTruthUnknownFieldTest() {
        val tracker = SignalTruthTracker()
        val obs = tracker.observation.value

        // Campos no observados aún deben ser nulos o UNVERIFIABLE
        assertNull(obs.sourceFormat)
        assertNull(obs.decodedFormat)
        assertNull(obs.outputConfig)
        assertEquals(BitPerfectStatus.UNVERIFIABLE, obs.bitPerfect)
    }

    @Test
    fun signalTruthDecoderAndOutputUpdatesTest() {
        val tracker = SignalTruthTracker()

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(96000)
            .setChannelCount(2)
            .build()

        tracker.onDecoderInputFormat(format)
        val obs1 = tracker.observation.value
        assertEquals("FLAC", obs1.sourceFormat?.codec)
        assertEquals(96000, obs1.sourceFormat?.sampleRate)
        assertEquals(2, obs1.sourceFormat?.channels)

        // Evento de decodificación PCM
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 96000, channels = 2, bitDepth = 24))
        val obs2 = tracker.observation.value
        assertEquals(24, obs2.decodedFormat?.bitDepth)
        assertEquals(96000, obs2.decodedFormat?.sampleRate)

        // Evento de inicialización de AudioTrack
        tracker.onAudioTrackInitialized(
            sampleRate = 96000,
            channelCount = 2,
            encoding = androidx.media3.common.C.ENCODING_PCM_24BIT,
        )
        val obs3 = tracker.observation.value
        assertEquals(24, obs3.outputConfig?.bitDepth)
        assertEquals(96000, obs3.outputConfig?.sampleRate)
    }

    @Test
    fun hotSwapAudioModeTest() {
        val audioEffects = MichiAudioEffects(mockContext)
        val replayGain = ReplayGainAudioProcessor()
        val modeManager = AudioModeManager(mockContext, audioEffects, replayGain)
        val tracker = SignalTruthTracker(audioModeManager = modeManager, audioEffects = audioEffects, replayGainProcessor = replayGain)

        modeManager.setMode(AudioProcessingMode.DSP)
        tracker.recompute()
        assertEquals(AudioProcessingMode.DSP, modeManager.currentMode.value)

        modeManager.setMode(AudioProcessingMode.TRANSPARENT)
        tracker.recompute()
        assertEquals(AudioProcessingMode.TRANSPARENT, modeManager.currentMode.value)
        assertFalse(tracker.observation.value.dspStatus.isReplayGainActive)
    }
}
