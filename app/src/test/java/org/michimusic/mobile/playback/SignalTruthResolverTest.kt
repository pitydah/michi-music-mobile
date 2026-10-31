package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.DspPipelineStatus
import org.michimusic.core.models.SignalEvidenceType
import org.michimusic.core.models.SignalFormat
import org.michimusic.core.models.SignalLayer
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class SignalTruthResolverTest {

    private val localEndpoint = PlaybackEndpoint.LocalPhone

    private val remoteHostEndpoint = PlaybackEndpoint(
        id = "desktop_host_1",
        name = "Studio Desktop",
        type = EndpointType.DESKTOP_PLAYER,
        isLocal = false,
        isConnected = true,
        capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"),
    )

    private val streamEndpoint = PlaybackEndpoint(
        id = "receiver_lite_1",
        name = "Michi Stream Speaker",
        type = EndpointType.STREAM_RECEIVER,
        isLocal = false,
        isConnected = true,
        capabilities = setOf("AUDIO_OUTPUT"),
    )

    private val testTrack = Track(
        id = "track_1",
        title = "Test Symphony",
        artist = "Test Artist",
        album = "Test Album",
        duration = 300000L,
        format = "flac",
        sampleRate = 96000,
        source = TrackSource.LOCAL,
    )

    @Test
    fun `remote host does NOT attribute local decoder or AudioTrack measurements (K46D-47)`() {
        // Local observation contains leftover measurements from previous local playback
        val localStaleObservation = AudioSignalObservation(
            sourceFormat = SignalFormat(codec = "FLAC", bitDepth = 24, sampleRate = 192000, channels = 2),
            decodedFormat = SignalFormat(codec = "PCM", bitDepth = 24, sampleRate = 192000, channels = 2),
            dspStatus = DspPipelineStatus(isEqActive = true, isReplayGainActive = true, replayGainAppliedDb = -3.0f),
            outputConfig = SignalFormat(codec = "AudioTrack_PCM", bitDepth = 24, sampleRate = 192000, channels = 2),
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = localStaleObservation,
            activeEndpoint = remoteHostEndpoint,
            currentTrack = testTrack,
            isRemote = true,
        )

        val decoding = items.first { it.layer == SignalLayer.DECODING }
        assertEquals(SignalEvidenceType.UNKNOWN, decoding.evidenceType)
        assertEquals("No observado", decoding.value)

        val processing = items.first { it.layer == SignalLayer.PROCESSING }
        assertEquals(SignalEvidenceType.UNKNOWN, processing.evidenceType)
        assertEquals("Desconocido", processing.value)

        val output = items.first { it.layer == SignalLayer.ANDROID_OUTPUT }
        assertEquals(SignalEvidenceType.UNKNOWN, output.evidenceType)
        assertEquals("No observado", output.value)

        val physical = items.first { it.layer == SignalLayer.PHYSICAL_DEVICE }
        assertEquals(SignalEvidenceType.UNKNOWN, physical.evidenceType)
        assertEquals("No verificado", physical.value)
    }

    @Test
    fun `michi stream correctly attributes local PCM decoding and network stream output`() {
        val streamObservation = AudioSignalObservation(
            sourceFormat = SignalFormat(codec = "FLAC", bitDepth = 24, sampleRate = 96000, channels = 2),
            decodedFormat = SignalFormat(codec = "PCM", bitDepth = 24, sampleRate = 96000, channels = 2),
            dspStatus = DspPipelineStatus(isEqActive = false, isReplayGainActive = false),
            outputConfig = null,
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = streamObservation,
            activeEndpoint = streamEndpoint,
            currentTrack = testTrack,
            isRemote = true,
        )

        val decoding = items.first { it.layer == SignalLayer.DECODING }
        assertEquals(SignalEvidenceType.OBSERVED, decoding.evidenceType)
        assertEquals("PCM 24/96", decoding.value)
        assertEquals("local_phone", decoding.originEndpointId)

        val output = items.first { it.layer == SignalLayer.ANDROID_OUTPUT }
        assertEquals(SignalEvidenceType.OBSERVED, output.evidenceType)
        assertTrue(output.value.contains("RTP Stream"))

        val physical = items.first { it.layer == SignalLayer.PHYSICAL_DEVICE }
        assertEquals(SignalEvidenceType.UNKNOWN, physical.evidenceType)
        assertEquals("No verificado", physical.value)
    }

    @Test
    fun `simultaneous ReplayGain and EQ are both displayed without masking each other`() {
        val dspBoth = AudioSignalObservation(
            dspStatus = DspPipelineStatus(
                isEqActive = true,
                isReplayGainActive = true,
                replayGainAppliedDb = -2.8f,
            )
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = dspBoth,
            activeEndpoint = localEndpoint,
            currentTrack = testTrack,
            isRemote = false,
        )

        val processing = items.first { it.layer == SignalLayer.PROCESSING }
        assertEquals(SignalEvidenceType.CONFIGURED, processing.evidenceType)
        assertTrue(processing.value.contains("EQ activo"))
        assertTrue(processing.value.contains("ReplayGain"))
        assertTrue(processing.value.contains("-2.8 dB"))
    }

    @Test
    fun `only EQ active displays EQ activo`() {
        val dspEqOnly = AudioSignalObservation(
            dspStatus = DspPipelineStatus(isEqActive = true, isReplayGainActive = false)
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = dspEqOnly,
            activeEndpoint = localEndpoint,
            currentTrack = testTrack,
            isRemote = false,
        )

        val processing = items.first { it.layer == SignalLayer.PROCESSING }
        assertEquals("EQ activo", processing.value)
    }

    @Test
    fun `only ReplayGain active displays ReplayGain activo with dB`() {
        val dspRgOnly = AudioSignalObservation(
            dspStatus = DspPipelineStatus(isEqActive = false, isReplayGainActive = true, replayGainAppliedDb = -4.5f)
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = dspRgOnly,
            activeEndpoint = localEndpoint,
            currentTrack = testTrack,
            isRemote = false,
        )

        val processing = items.first { it.layer == SignalLayer.PROCESSING }
        assertEquals("ReplayGain activo (-4.5 dB)", processing.value)
    }

    @Test
    fun `neither EQ nor ReplayGain active displays Sin EQ activo`() {
        val dspNone = AudioSignalObservation(
            dspStatus = DspPipelineStatus(isEqActive = false, isReplayGainActive = false)
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = dspNone,
            activeEndpoint = localEndpoint,
            currentTrack = testTrack,
            isRemote = false,
        )

        val processing = items.first { it.layer == SignalLayer.PROCESSING }
        assertEquals("Sin EQ activo", processing.value)
    }

    @Test
    fun `no 0 kHz or NaN strings are generated`() {
        val zeroRateObservation = AudioSignalObservation(
            sourceFormat = SignalFormat(codec = "UNKNOWN", bitDepth = 0, sampleRate = 0, channels = 0),
            decodedFormat = SignalFormat(codec = "PCM", bitDepth = 0, sampleRate = 0, channels = 0),
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = zeroRateObservation,
            activeEndpoint = localEndpoint,
            currentTrack = null,
            isRemote = false,
        )

        items.forEach { item ->
            assertFalse(item.value.contains("0 kHz"))
            assertFalse(item.value.contains("NaN"))
            assertFalse(item.value.contains("null"))
        }
    }

    @Test
    fun `remotePlayback_neverInheritsLocalSourceFormat`() {
        // Local observation contains leftover measurements from previous local playback (FLAC 24/192)
        val localStaleObservation = AudioSignalObservation(
            sourceFormat = SignalFormat(codec = "FLAC", bitDepth = 24, sampleRate = 192000, channels = 2),
            decodedFormat = SignalFormat(codec = "PCM", bitDepth = 24, sampleRate = 192000, channels = 2),
            observedAtMs = 5000L,
        )

        // Case 1: Remote track has AAC metadata
        val remoteTrackWithMetadata = Track(
            id = "remote_1",
            title = "Remote Stream",
            artist = "Remote Artist",
            format = "aac",
            sampleRate = 44100,
            bitrate = 256,
            source = TrackSource.STREAMING,
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = localStaleObservation,
            activeEndpoint = remoteHostEndpoint,
            currentTrack = remoteTrackWithMetadata,
            isRemote = true,
        )

        val sourceItem = items.first { it.layer == SignalLayer.SOURCE }
        assertEquals(SignalEvidenceType.DECLARED, sourceItem.evidenceType)
        // Must reflect remote track, NOT local FLAC 24/192
        assertTrue("Expected AAC in remote source, got: ${sourceItem.value}", sourceItem.value.startsWith("AAC"))
        assertFalse("Must not contain FLAC from stale observation", sourceItem.value.contains("FLAC"))

        // Case 2: Remote track without format or null track -> UNKNOWN / No declarado
        val remoteTrackNoFormat = Track(
            id = "remote_2",
            title = "Remote Unknown",
            artist = "Unknown",
            format = "",
            source = TrackSource.STREAMING,
        )

        val itemsNoFormat = SignalTruthResolver.resolveProvenance(
            observation = localStaleObservation,
            activeEndpoint = remoteHostEndpoint,
            currentTrack = remoteTrackNoFormat,
            isRemote = true,
        )
        val sourceItemNoFormat = itemsNoFormat.first { it.layer == SignalLayer.SOURCE }
        assertEquals(SignalEvidenceType.UNKNOWN, sourceItemNoFormat.evidenceType)
        assertEquals("No declarado", sourceItemNoFormat.value)
    }

    @Test
    fun `invalidSampleRateOrBitDepth_isUnknown_neverDefaultsTo16_44`() {
        val zeroRateObservation = AudioSignalObservation(
            decodedFormat = SignalFormat(codec = "PCM", bitDepth = 0, sampleRate = 0, channels = 2),
            observedAtMs = 1000L,
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = zeroRateObservation,
            activeEndpoint = localEndpoint,
            currentTrack = testTrack,
            isRemote = false,
        )

        val decoding = items.first { it.layer == SignalLayer.DECODING }
        // K47-03: Must NOT fabricate PCM 16/44.1 or claim OBSERVED
        assertEquals(SignalEvidenceType.UNKNOWN, decoding.evidenceType)
        assertEquals("No observado", decoding.value)
        assertFalse(decoding.value.contains("16/44.1"))
    }

    @Test
    fun `freshness_comesFromObservationTimestamp_neverInventedSystemCurrentTime`() {
        val testTimestamp = 987654321L
        val observation = AudioSignalObservation(
            decodedFormat = SignalFormat(codec = "PCM", bitDepth = 24, sampleRate = 48000, channels = 2),
            outputConfig = SignalFormat(codec = "AudioTrack", bitDepth = 24, sampleRate = 48000, channels = 2),
            dspStatus = DspPipelineStatus(isEqActive = true),
            observedAtMs = testTimestamp,
        )

        val items = SignalTruthResolver.resolveProvenance(
            observation = observation,
            activeEndpoint = localEndpoint,
            currentTrack = testTrack,
            isRemote = false,
        )

        val decoding = items.first { it.layer == SignalLayer.DECODING }
        assertEquals(testTimestamp, decoding.freshnessMs)

        val output = items.first { it.layer == SignalLayer.ANDROID_OUTPUT }
        assertEquals(testTimestamp, output.freshnessMs)

        // Physical device is unverified -> 0L
        val physical = items.first { it.layer == SignalLayer.PHYSICAL_DEVICE }
        assertEquals(0L, physical.freshnessMs)
    }
}
