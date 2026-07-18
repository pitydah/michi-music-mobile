package org.michimusic.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
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
        val buffer = pToBuffer(p)
        assertEquals(0, buffer.capacity()) // no samples processed = no output
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
        assertEquals(Short.MAX_VALUE, sample.toInt())
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

    private fun processSingleSample(p: ReplayGainAudioProcessor, input: Short): Short {
        val buf = ByteBuffer.allocate(2).putShort(0, input)
        buf.position(0)
        p.queueInput(buf)
        val out = p.getOutput()
        return if (out != null && out.hasRemaining()) out.short else 0
    }

    private fun pToBuffer(p: ReplayGainAudioProcessor): ByteBuffer {
        return p.getOutput() ?: ByteBuffer.allocate(0)
    }
}
