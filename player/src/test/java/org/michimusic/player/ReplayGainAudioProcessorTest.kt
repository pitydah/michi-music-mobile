package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class ReplayGainAudioProcessorTest {

    private fun processor() = ReplayGainAudioProcessor()

    private fun track(
        trackGain: Float = Float.NaN,
        albumGain: Float = Float.NaN,
    ) = org.michimusic.core.models.Track(
        id = "test",
        title = "Test",
        artist = "Artist",
        album = "Album",
        duration = 1000L,
        filepath = "/test.mp3",
        replayGainTrack = trackGain,
        replayGainAlbum = albumGain,
    )

    @Test
    fun offMode_volumeIs1() {
        val p = processor()
        p.configure(ReplayGainMode.OFF, ReplayGainPreAmp())
        p.onSongChanged(track(trackGain = -8.5f))
        val sample = processSingleSample(p, 10000.toShort())
        assertEquals(10000.toShort(), sample)
    }

    @Test
    fun trackMode_appliesTrackGain() {
        val p = processor()
        p.configure(ReplayGainMode.TRACK, ReplayGainPreAmp())
        p.onSongChanged(track(trackGain = -6.0f))
        val expectedVol = 10f.pow((-6f) / 20f)
        val sample = processSingleSample(p, 10000.toShort())
        val expected = (10000 * expectedVol).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        assertEquals(expected.toShort(), sample)
    }

    @Test
    fun albumMode_fallsBackToTrackGain() {
        val p = processor()
        p.configure(ReplayGainMode.ALBUM, ReplayGainPreAmp())
        p.onSongChanged(track(trackGain = -3.0f, albumGain = Float.NaN))
        val sample = processSingleSample(p, 8000.toShort())
        val expectedVol = 10f.pow((-3f) / 20f)
        val expected = (8000 * expectedVol).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        assertEquals(expected.toShort(), sample)
    }

    @Test
    fun trimsClipping() {
        val p = processor()
        p.configure(ReplayGainMode.TRACK, ReplayGainPreAmp(with = 15f))
        p.onSongChanged(track(trackGain = 0f))
        val sample = processSingleSample(p, 30000.toShort())
        assertEquals(Short.MAX_VALUE, sample)
    }

    @Test
    fun negativeGain_lowersVolume() {
        val p = processor()
        p.configure(ReplayGainMode.TRACK, ReplayGainPreAmp())
        p.onSongChanged(track(trackGain = -12.0f))
        val vol = 10f.pow((-12f) / 20f)
        assertTrue(vol < 0.5f)
        val sample = processSingleSample(p, 20000.toShort())
        val expected = (20000 * vol).toInt().toShort()
        assertEquals(expected, sample)
    }

    @Test
    fun preAmpWith_addsToGain() {
        val p = processor()
        p.configure(ReplayGainMode.TRACK, ReplayGainPreAmp(with = 3f))
        p.onSongChanged(track(trackGain = -6.0f))
        val vol = 10f.pow((-3f) / 20f)
        val sample = processSingleSample(p, 16000.toShort())
        val expected = (16000 * vol).toInt().toShort()
        assertEquals(expected, sample)
    }

    @Test
    fun noReplayGainData_defaultVolume() {
        val p = processor()
        p.configure(ReplayGainMode.DYNAMIC, ReplayGainPreAmp())
        p.onSongChanged(track())
        val vol = 10f.pow(0f / 20f)
        assertEquals(1f, vol, 0.001f)
        val sample = processSingleSample(p, 12000.toShort())
        assertEquals(12000.toShort(), sample)
    }

    @Test
    fun dspPreampChangesActualPcmWhenReplayGainIsOff() {
        val p = processor()
        p.configure(ReplayGainMode.OFF, ReplayGainPreAmp())
        p.onSongChanged(track())
        p.setDspPreampDb(6f)
        assertEquals(6f, p.currentDspPreampDb, 0.001f)
        assertEquals(1f, p.currentReplayGainVolume, 0.001f)
        assertTrue(!p.isGainActive) // preamp is NOT ReplayGain
        val expected = (10000 * 10f.pow(6f / 20f)).toInt().toShort()
        assertEquals(expected, processSingleSample(p, 10000.toShort()))

        p.isBypassed = true
        assertEquals(0f, p.currentDspPreampDb, 0.001f)
        assertEquals(10000.toShort(), processSingleSample(p, 10000.toShort()))
    }

    @Test
    fun nonFiniteDspPreampFailsClosed() {
        val p = processor()
        p.configure(ReplayGainMode.OFF, ReplayGainPreAmp())
        p.onSongChanged(track())
        p.setDspPreampDb(Float.NaN)
        assertEquals(0f, p.currentDspPreampDb, 0.001f)
        assertEquals(10000.toShort(), processSingleSample(p, 10000.toShort()))
    }

    @Test
    fun dspPreampClippingIsCountedUpstreamAndResetAtTrackChange() {
        val p = processor()
        p.configure(ReplayGainMode.OFF, ReplayGainPreAmp())
        p.onSongChanged(track())
        p.setDspPreampDb(12f)
        processSingleSample(p, 20000.toShort())
        assertTrue(p.pcmClipSamples > 0L)
        p.onSongChanged(track())
        assertEquals(0L, p.pcmClipSamples)
    }

    @Test
    fun upstreamProtectionCapsSummedGainBeforeRtpAndPeq() {
        val p = processor()
        p.setUpstreamUnityProtection(true)
        p.configure(ReplayGainMode.TRACK, ReplayGainPreAmp(with = 6f))
        p.onSongChanged(track(trackGain = 4f))
        p.setDspPreampDb(10f)
        assertEquals(1f, p.currentVolume, 0.0001f)
        assertTrue(p.safetyReductionDb <= -19.9f)
        assertEquals(30000.toShort(), processSingleSample(p, 30000.toShort()))
        assertEquals(0L, p.pcmClipSamples)
    }

    @Test
    fun upstreamProtectionKeepsAttenuationAndTransparentBypass() {
        val p = processor()
        p.setUpstreamUnityProtection(true)
        p.configure(ReplayGainMode.TRACK, ReplayGainPreAmp())
        p.onSongChanged(track(trackGain = -9f))
        assertEquals(0f, p.safetyReductionDb, 0.001f)
        assertTrue(p.currentVolume < 1f)
        p.isBypassed = true
        assertEquals(0f, p.safetyReductionDb, 0.001f)
        assertEquals(20000.toShort(), processSingleSample(p, 20000.toShort()))
    }

    private fun processSingleSample(p: ReplayGainAudioProcessor, input: Short): Short {
        p.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        p.flush()
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(input)
        buf.flip()
        p.queueInput(buf)
        val out = p.output.order(ByteOrder.LITTLE_ENDIAN)
        return if (out.hasRemaining()) out.getShort() else 0
    }
}
