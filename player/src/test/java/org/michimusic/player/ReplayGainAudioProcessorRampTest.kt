package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM-level regression checks: byte-exact unity, frame-aligned fades and four encodings. */
class ReplayGainAudioProcessorRampTest {
    private fun player(encoding: Int, channels: Int = 1): ReplayGainAudioProcessor {
        val p = ReplayGainAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(48000, channels, encoding))
        p.flush()
        p.configure(ReplayGainMode.TRACK, ReplayGainPreAmp())
        p.onSongChanged(track(-12f))
        return p
    }

    private fun track(gain: Float) = org.michimusic.core.models.Track(
        id = "ramp-test", title = "Test", artist = "Artist", album = "Album",
        duration = 1000L, filepath = "/test.flac", replayGainTrack = gain,
    )

    private fun samples(p: ReplayGainAudioProcessor, frames: Int, channels: Int, encoding: Int): List<Double> {
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val input = ByteBuffer.allocate(frames * channels * bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> input.putShort(16000.toShort())
                C.ENCODING_PCM_24BIT -> { input.put(0); input.put(0); input.put(64) }
                C.ENCODING_PCM_32BIT -> input.putInt(0x40000000)
                C.ENCODING_PCM_FLOAT -> input.putFloat(0.5f)
            }
        }
        input.flip()
        p.queueInput(input)
        assertEquals(input.limit(), input.position())
        val output = p.output.order(ByteOrder.LITTLE_ENDIAN)
        return (0 until frames * channels).map {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> output.short.toDouble() / 16000.0
                C.ENCODING_PCM_24BIT -> {
                    val a = output.get().toInt() and 255
                    val b = output.get().toInt() and 255
                    val c = output.get().toInt()
                    ((a or (b shl 8) or (c shl 16)) shl 8 shr 8).toDouble() / 0x400000
                }
                C.ENCODING_PCM_32BIT -> output.int.toDouble() / 0x40000000
                else -> output.float.toDouble() / 0.5
            }
        }
    }

    private fun verifyTransition(encoding: Int) {
        val p = player(encoding)
        val before = samples(p, 4, 1, encoding).last()
        assertTrue(before in 0.24..0.26)
        p.configure(ReplayGainMode.OFF, ReplayGainPreAmp()) // no flush, no AudioTrack rebuild
        val ramp = samples(p, 480, 1, encoding)
        assertTrue("$encoding first frame $before / ${ramp.first()}", ramp.first() > before)
        assertTrue(ramp.first() < 0.30)
        assertTrue(ramp[120] > ramp.first())
        assertTrue(ramp[240] > ramp[120])
        assertTrue(ramp.last() > 0.999)
        assertEquals(1.0, samples(p, 2, 1, encoding).last(), 0.0003)
    }

    @Test fun pcm16RampsWithinTenMilliseconds() = verifyTransition(C.ENCODING_PCM_16BIT)
    @Test fun pcm24RampsWithinTenMilliseconds() = verifyTransition(C.ENCODING_PCM_24BIT)
    @Test fun pcm32RampsWithinTenMilliseconds() = verifyTransition(C.ENCODING_PCM_32BIT)
    @Test fun floatRampsWithinTenMilliseconds() = verifyTransition(C.ENCODING_PCM_FLOAT)

    @Test fun stereoSharesEnvelopePerFrame() {
        val p = player(C.ENCODING_PCM_16BIT, channels = 2)
        samples(p, 1, 2, C.ENCODING_PCM_16BIT)
        p.configure(ReplayGainMode.OFF, ReplayGainPreAmp())
        val four = samples(p, 2, 2, C.ENCODING_PCM_16BIT)
        assertEquals(four[0], four[1], 0.000001)
        assertEquals(four[2], four[3], 0.000001)
        assertTrue(four[2] > four[0])
    }

    @Test fun unityBypassPreservesFloatNanPayloadBits() {
        val p = ReplayGainAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_FLOAT))
        p.flush()
        val payload = 0x7fa00001
        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload)
        input.flip()
        p.queueInput(input)
        assertEquals(payload, p.output.order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test fun flushDoesNotCarryOldGainAcrossTracks() {
        val p = player(C.ENCODING_PCM_16BIT)
        samples(p, 10, 1, C.ENCODING_PCM_16BIT)
        p.configure(ReplayGainMode.OFF, ReplayGainPreAmp())
        samples(p, 4, 1, C.ENCODING_PCM_16BIT)
        p.flush()
        assertEquals(1.0, samples(p, 1, 1, C.ENCODING_PCM_16BIT).first(), 0.001)
    }
}
