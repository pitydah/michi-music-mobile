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
    fun stoppedSignalCannotClaimActiveReplayGain() {
        val gain = ReplayGainAudioProcessor()
        val tracker = SignalTruthTracker(replayGainProcessor = gain)
        tracker.onPlaybackStopped()
        assertFalse(tracker.observation.value.dspStatus.isReplayGainActive)
        assertEquals(0f, tracker.observation.value.dspStatus.replayGainAppliedDb)
    }

    @Test
    fun playbackActivityMustBeExplicitlyReported() {
        val tracker = SignalTruthTracker()
        tracker.setPlaybackActive(true)
        tracker.setPlaybackActive(false)
        assertNull(tracker.observation.value.sourceFormat)
        assertFalse(tracker.observation.value.dspStatus.isEqActive)
    }

    @Test
    fun turningEqOnCannotOverrideTransparentMode() {
        val effects = MichiAudioEffects(mockContext)
        val gain = ReplayGainAudioProcessor()
        val manager = AudioModeManager(mockContext, effects, gain)
        manager.setMode(AudioProcessingMode.TRANSPARENT)
        effects.setEnabled(true)
        assertTrue(effects.isBypassedByMode)
        assertFalse(effects.effectsState.value.isEnabled)
        assertTrue(gain.isBypassed)
        manager.setMode(AudioProcessingMode.DSP)
        assertTrue(effects.effectsState.value.isEnabled)
        assertFalse(gain.isBypassed)
    }

    @Test
    fun clearedTrackRemovesAllMedia3Measurements() {
        val tracker = SignalTruthTracker()
        tracker.onDecoderInputFormat(Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setChannelCount(1)
            .setSampleRate(44100).build())
        tracker.onPcmFormatDecoded(PcmFormat(44100, 1, 16))
        tracker.onAudioTrackInitialized(44100, 1, androidx.media3.common.C.ENCODING_PCM_16BIT)
        tracker.onTrackChanged(null)
        assertNull(tracker.observation.value.sourceFormat)
        assertNull(tracker.observation.value.decodedFormat)
        assertNull(tracker.observation.value.outputConfig)
    }

    @Test
    fun degradedAudioSinkCannotPresentOldPcmMetrics() {
        val tracker = SignalTruthTracker()
        tracker.onPcmFormatDecoded(PcmFormat(48000, 2, 24))
        tracker.markInstrumentationDegraded()
        assertNull(tracker.observation.value.decodedFormat)
        tracker.onPcmFormatDecoded(PcmFormat(96000, 2, 16))
        assertNull(tracker.observation.value.decodedFormat)
        tracker.onTrackChanged(null)
        assertNull(tracker.observation.value.decodedFormat)
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

    @Test
    fun stoppedPlaybackClearsOldDecoderAndAudioTrackEvidence() {
        val tracker = SignalTruthTracker()
        tracker.onDecoderInputFormat(Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(96000)
            .setChannelCount(2)
            .build())
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 96000, channels = 2, bitDepth = 24))
        tracker.onAudioTrackInitialized(96000, 2, androidx.media3.common.C.ENCODING_PCM_24BIT)
        tracker.onPlaybackStopped()
        assertNull(tracker.observation.value.sourceFormat)
        assertNull(tracker.observation.value.decodedFormat)
        assertNull(tracker.observation.value.outputConfig)
    }

    @Test
    fun degradedSinkRejectsEvenLaterOutputCallbacks() {
        val tracker = SignalTruthTracker()
        tracker.markInstrumentationDegraded()
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 192000, channels = 2, bitDepth = 32))
        tracker.onAudioTrackInitialized(192000, 2, androidx.media3.common.C.ENCODING_PCM_32BIT)
        assertNull(tracker.observation.value.decodedFormat)
        assertNull(tracker.observation.value.outputConfig)
    }

    @Test
    fun releasedAudioTrackInvalidatesItsLastConfiguration() {
        val tracker = SignalTruthTracker()
        tracker.onAudioTrackInitialized(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        assertEquals(44100, tracker.observation.value.outputConfig?.sampleRate)
        tracker.onAudioTrackReleased()
        assertNull(tracker.observation.value.outputConfig)
    }

    @Test
    fun lateAudioTrackReleaseFromStaleGenerationDoesNotInvalidateActiveOutput() {
        val tracker = SignalTruthTracker()
        tracker.setPlaybackActive(true)

        // AudioTrack A (generation 1)
        tracker.onAudioTrackInitialized(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT, generation = 1L)
        assertEquals(44100, tracker.observation.value.outputConfig?.sampleRate)

        // AudioTrack B (generation 2) activated
        tracker.onAudioTrackInitialized(96000, 2, androidx.media3.common.C.ENCODING_PCM_24BIT, generation = 2L)
        assertEquals(96000, tracker.observation.value.outputConfig?.sampleRate)
        assertEquals(24, tracker.observation.value.outputConfig?.bitDepth)

        // Late release callback from Track A arrives
        tracker.onAudioTrackReleased(generation = 1L)

        // Track B measurements must be completely preserved!
        assertEquals(96000, tracker.observation.value.outputConfig?.sampleRate)
        assertEquals(24, tracker.observation.value.outputConfig?.bitDepth)

        // Releasing Track B (generation 2) clears output
        tracker.onAudioTrackReleased(generation = 2L)
        assertNull(tracker.observation.value.outputConfig)
    }

    @Test
    fun gaplessTrackChangeMaintainsActiveOutputAndDecodedMeasurements() {
        val tracker = SignalTruthTracker()
        tracker.setPlaybackActive(true)

        // Track 1 decoded & initialized
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 44100, channels = 2, bitDepth = 16))
        tracker.onAudioTrackInitialized(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT, generation = 1L)

        assertEquals(44100, tracker.observation.value.decodedFormat?.sampleRate)
        assertEquals(44100, tracker.observation.value.outputConfig?.sampleRate)

        // Gapless change to Track 2 (same pipeline)
        val dummyTrack = org.michimusic.core.models.Track(
            id = "t2",
            title = "Track 2",
            artist = "Artist",
            album = "Album",
            duration = 180L,
            filepath = "/music/t2.flac"
        )
        tracker.onTrackChanged(dummyTrack)

        // Measurements from continuous pipeline are NOT wiped out
        assertEquals(44100, tracker.observation.value.decodedFormat?.sampleRate)
        assertEquals(44100, tracker.observation.value.outputConfig?.sampleRate)
    }

    @Test
    fun routeObserverIgnoresStaleAudioTrackReleaseFromPreviousGeneration() {
        val observer = DefaultAudioRouteObserver()
        val requested = OutputDescriptor(
            androidDeviceId = 101,
            name = "DAC USB Fiio",
            androidType = android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            announcedSampleRates = listOf(96000),
            announcedChannelCounts = listOf(2),
            announcedEncodings = listOf(android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED),
        )
        observer.onDeviceRequested(requested)
        assertEquals(AudioRouteStatus.Requested(requested), observer.routeStatus.value)

        // Track 1 initialized with generation 1
        observer.attachAudioTrack(mockkAudioTrack(), generation = 1L)

        // Track 2 initialized with generation 2
        val track2 = mockkAudioTrack()
        observer.attachAudioTrack(track2, generation = 2L)

        // Late release callback arrives for Track 1 (generation 1)
        observer.detachAudioTrack(audioTrack = null, generation = 1L)

        // Track 2 must still remain observed / active (not downgraded or reset to LastObserved)
        assertEquals(AudioRouteStatus.Requested(requested), observer.routeStatus.value)

        // Releasing Track 2 (generation 2) safely completes detachment
        observer.detachAudioTrack(audioTrack = null, generation = 2L)
    }

    @Test
    fun adversarialOutOfOrderReleasesBetweenDifferentConfigsPreservesActiveSinkTruth() {
        val tracker = SignalTruthTracker()
        tracker.setPlaybackActive(true)

        // Config A (44.1kHz, 16bit, gen 1)
        tracker.onAudioTrackInitialized(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT, generation = 1L)
        // Config B (96kHz, 24bit, gen 2)
        tracker.onAudioTrackInitialized(96000, 2, androidx.media3.common.C.ENCODING_PCM_24BIT, generation = 2L)
        // Config C (192kHz, 32bit, gen 3)
        tracker.onAudioTrackInitialized(192000, 2, androidx.media3.common.C.ENCODING_PCM_32BIT, generation = 3L)

        assertEquals(192000, tracker.observation.value.outputConfig?.sampleRate)
        assertEquals(32, tracker.observation.value.outputConfig?.bitDepth)

        // Adversarial interleaving: Config B (gen 2) finishes async release first
        tracker.onAudioTrackReleased(generation = 2L)
        assertEquals(192000, tracker.observation.value.outputConfig?.sampleRate)
        assertEquals(32, tracker.observation.value.outputConfig?.bitDepth)

        // Then Config A (gen 1) finishes async release
        tracker.onAudioTrackReleased(generation = 1L)
        assertEquals(192000, tracker.observation.value.outputConfig?.sampleRate)
        assertEquals(32, tracker.observation.value.outputConfig?.bitDepth)

        // Finally active Config C (gen 3) releases
        tracker.onAudioTrackReleased(generation = 3L)
        assertNull(tracker.observation.value.outputConfig)
    }

    private fun mockkAudioTrack(): android.media.AudioTrack {
        return Proxy.newProxyInstance(
            android.media.AudioTrack::class.java.classLoader,
            arrayOf(),
        ) { _, _, _ -> null } as? android.media.AudioTrack ?: io.mockk.mockk(relaxed = true)
    }
}

