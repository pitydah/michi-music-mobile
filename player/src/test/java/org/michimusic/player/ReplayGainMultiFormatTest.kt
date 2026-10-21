package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplayGainMultiFormatTest {

    @Test
    fun replayGain16BitClippingTest() {
        val processor = ReplayGainAudioProcessor()
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val track = org.michimusic.core.models.Track(
            id = "t1",
            title = "Test Track",
            artist = "Artist",
            album = "Album",
            duration = 1000L,
            filepath = "/music/song.flac",
            replayGainTrack = 20f, // +20dB boost (factor 10.0x) -> provocará clipping
        )
        processor.configure(ReplayGainMode.TRACK, ReplayGainPreAmp(0f, 0f))
        processor.onSongChanged(track)

        // Entrada con valor 10000 (16-bit). Multiplicado por 10x daría 100000, excediendo 32767
        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(10000.toShort())
        input.putShort((-10000).toShort())
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        val outSample1 = output.getShort()
        val outSample2 = output.getShort()

        // Debe haber sido limitado sin overflow numérico
        assertEquals(Short.MAX_VALUE, outSample1)
        assertEquals(Short.MIN_VALUE, outSample2)
    }

    @Test
    fun replayGain24BitProcessingTest() {
        val processor = ReplayGainAudioProcessor()
        val format = AudioProcessor.AudioFormat(96000, 1, C.ENCODING_PCM_24BIT)
        processor.configure(format)
        processor.flush()

        val track = org.michimusic.core.models.Track(
            id = "t2",
            title = "HiRes Track",
            artist = "Artist",
            album = "Album",
            duration = 1000L,
            filepath = "/music/hires.flac",
            replayGainTrack = 6.02f, // ~ +6dB (duplica la amplitud: factor 2.0x)
        )
        processor.configure(ReplayGainMode.TRACK, ReplayGainPreAmp(0f, 0f))
        processor.onSongChanged(track)

        // Muestra de 24 bits: valor 100000 (0x0186A0)
        // Little endian: byte0 = 0xA0, byte1 = 0x86, byte2 = 0x01
        val input = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        input.put(0xA0.toByte())
        input.put(0x86.toByte())
        input.put(0x01.toByte())
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        val b0 = output.get().toInt() and 0xFF
        val b1 = output.get().toInt() and 0xFF
        val b2 = output.get().toInt()
        val sampleOut = (b2 shl 16) or (b1 shl 8) or b0

        // Duplicado debe ser aproximadamente 200000 (+/- tolerancia)
        assertTrue(sampleOut in 199000..201000)
    }

    @Test
    fun replayGainFloatProcessingTest() {
        val processor = ReplayGainAudioProcessor()
        val format = AudioProcessor.AudioFormat(192000, 1, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()

        val track = org.michimusic.core.models.Track(
            id = "t3",
            title = "Float Track",
            artist = "Artist",
            album = "Album",
            duration = 1000L,
            filepath = "/music/float.wav",
            replayGainTrack = -6.02f, // ~ -6dB (divide a la mitad: factor 0.5x)
        )
        processor.configure(ReplayGainMode.TRACK, ReplayGainPreAmp(0f, 0f))
        processor.onSongChanged(track)

        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        input.putFloat(0.8f)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        val outFloat = output.getFloat()
        assertEquals(0.4f, outFloat, 0.01f)
    }

    @Test
    fun transparentModeBypassesReplayGainTest() {
        val processor = ReplayGainAudioProcessor()
        val format = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val track = org.michimusic.core.models.Track(
            id = "t4",
            title = "Track",
            artist = "Artist",
            album = "Album",
            duration = 1000L,
            filepath = "/music/song.mp3",
            replayGainTrack = -10f,
        )
        processor.configure(ReplayGainMode.TRACK, ReplayGainPreAmp(0f, 0f))
        processor.onSongChanged(track)

        // Al activar bypass transparente, la ganancia aplicada debe ser exactamente 1.0 (bit a bit)
        processor.isBypassed = true

        val input = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(1234.toShort())
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        val outSample = output.getShort()
        assertEquals(1234.toShort(), outSample)
    }
}
