package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HeadphonePeqAudioProcessorTest {
    private val testGate = HeadphonePeqAudioProcessor.RouteGate { true }
    private fun pcm16(processor: HeadphonePeqAudioProcessor, input: ShortArray): ShortArray {
        processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val buffer = ByteBuffer.allocate(input.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        input.forEach(buffer::putShort)
        buffer.flip()
        processor.queueInput(buffer)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(output.remaining() / 2) { output.short }
    }

    @Test fun routeRevocationImmediatelyBypassesWithoutPublishingProcessing() {
        val processor = HeadphonePeqAudioProcessor()
        var allowed = true
        val guard = HeadphonePeqAudioProcessor.RouteGate { allowed }
        processor.setProfile(listOf(PeqBand(180f, 6f, 0.9f)), true, guard)
        processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        fun feed(): Short {
            val b = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(16000); b.flip(); processor.queueInput(b)
            return processor.output.order(ByteOrder.LITTLE_ENDIAN).short
        }
        feed()
        assertTrue(processor.isApplied)
        val processedBefore = processor.framesProcessed
        allowed = false
        assertEquals(16000, feed().toInt())
        assertFalse(processor.isApplied)
        assertEquals(processedBefore, processor.framesProcessed)
    }

    @Test fun bypass_preservesPcm16ByteForByte() {
        val processor = HeadphonePeqAudioProcessor()
        processor.setProfile(listOf(PeqBand(1000f, 6f, 1f)), active = false, gate = testGate)
        val samples = shortArrayOf(32767, 0, -32768, 1337, -20000)
        assertTrue(samples.contentEquals(pcm16(processor, samples)))
        assertEquals(0f, processor.appliedHeadroomDb)
    }

    @Test fun flatBands_doNotIntroduceUnexpectedGain() {
        val processor = HeadphonePeqAudioProcessor()
        processor.setProfile(defaultPeqBands(), active = true, gate = testGate)
        val samples = ShortArray(256) { (it * 72 - 7000).toShort() }
        val result = pcm16(processor, samples)
        assertTrue(result.indices.all { kotlin.math.abs(result[it].toInt() - samples[it].toInt()) <= 1 })
    }

    @Test fun boostedBandReservesHeadroomAndClipsAreCounted() {
        val processor = HeadphonePeqAudioProcessor()
        processor.setProfile(listOf(PeqBand(180f, 3f, 0.8f), PeqBand(1800f, 2f, 1f)), active = true, gate = testGate)
        val result = pcm16(processor, ShortArray(600) { 12000 })
        assertEquals(5f, processor.appliedHeadroomDb, 0.001f)
        assertEquals(600, result.size)
        assertTrue(result.all { it.toInt() in Short.MIN_VALUE..Short.MAX_VALUE })
    }

    @Test fun sixFullBoostBandsRequireAllHeadroomWithoutHiddenCap() {
        val allBoost = List(MICHI_PEQ_BAND_COUNT) { PeqBand(1000f + 350f * it, 12f, 1f) }
        assertEquals(72f, peqHeadroomDb(allBoost), 0.001f)
        val processor = HeadphonePeqAudioProcessor()
        processor.setProfile(allBoost, active = true, gate = testGate)
        val result = pcm16(processor, ShortArray(100) { 8000 })
        assertEquals(72f, processor.appliedHeadroomDb, 0.001f)
        assertEquals(100, result.size)
    }

    @Test fun reconfiguringSampleRateRebuildsBankBeforeQueueInput() {
        val processor = HeadphonePeqAudioProcessor()
        processor.setProfile(listOf(PeqBand(600f, 4f, 1f)), true, testGate)
        for (rate in listOf(44100, 96000, 192000, 48000)) {
            processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            processor.flush()
            val input = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
            repeat(4) { input.putShort(8000); input.putShort(-8000) }
            input.flip()
            processor.queueInput(input)
            assertEquals("Expected all samples at sampleRate=$rate", 16, processor.output.remaining())
            assertTrue("PEQ not effective at sampleRate=$rate", processor.isApplied)
        }
    }

    @Test fun untrustedNaNAndInfiniteValues_areNormalized() {
        val safe = PeqBand(Float.NaN, Float.POSITIVE_INFINITY, 0f).safe()
        assertTrue(safe.frequencyHz.isFinite())
        assertTrue(safe.gainDb.isFinite())
        assertTrue(safe.q >= 0.3f)
        assertEquals(0f, peqHeadroomDb(listOf(safe)))
    }

    @Test fun stereoBypass_hasReadableOutputAndPreservesEveryByte() {
        val processor = HeadphonePeqAudioProcessor()
        processor.setProfile(listOf(PeqBand(1200f, 3f, 1f)), active = false, gate = testGate)
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val b = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(10); b.putShort(-11); b.putShort(33); b.putShort(-44); b.putShort(55); b.putShort(-66)
        b.flip()
        processor.queueInput(b)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, output.remaining())
        assertEquals(10, output.short.toInt())
        assertEquals(-11, output.short.toInt())
        assertEquals(33, output.short.toInt())
        assertEquals(-44, output.short.toInt())
        assertEquals(55, output.short.toInt())
        assertEquals(-66, output.short.toInt())
    }

    @Test fun activePeq_publishesFullFramesAndValidHeadroom() {
        val processor = HeadphonePeqAudioProcessor()
        processor.setProfile(listOf(PeqBand(1000f, 6f, 1f)), active = true, gate = testGate)
        processor.configure(AudioProcessor.AudioFormat(96000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val b = ByteBuffer.allocateDirect(400).order(ByteOrder.LITTLE_ENDIAN)
        repeat(100) { b.putShort(5000); b.putShort(-5000) }
        b.flip()
        processor.queueInput(b)
        assertEquals(400, processor.output.remaining())
        assertTrue(processor.isApplied)
        assertEquals(6f, processor.appliedHeadroomDb, 0.001f)
    }

    @Test fun profileRevisionsAndFlushDoNotReusePreviousIirMemory() {
        val processor = HeadphonePeqAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.setProfile(listOf(PeqBand(200f, 6f, 0.7f)), true, testGate)
        processor.flush()
        fun render() {
            val b = ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN)
            repeat(32) { b.putShort(7000); b.putShort(-7000) }; b.flip()
            processor.queueInput(b)
            assertEquals(128, processor.output.remaining())
        }
        render()
        val first = processor.processingRevision
        processor.setProfile(listOf(PeqBand(900f, -5f, 1.4f)), true, testGate)
        render()
        assertTrue(processor.processingRevision > first)
        processor.flush()
        assertEquals(0L, processor.framesProcessed)
        assertEquals(-1L, processor.processingRevision)
        assertFalse(processor.isApplied)
        render()
        assertTrue(processor.isApplied)
    }

    @Test fun incompletePcmFrameFailsExplicitlyWithoutProducingBadChannel() {
        val processor = HeadphonePeqAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val b = ByteBuffer.allocateDirect(3)
        b.put(byteArrayOf(1, 2, 3)); b.flip()
        var rejected = false
        try { processor.queueInput(b) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun packed24Float32AndMultichannelRetainFrameSizes() {
        val formats = listOf(C.ENCODING_PCM_24BIT to 3, C.ENCODING_PCM_32BIT to 4,
            C.ENCODING_PCM_FLOAT to 4)
        for ((encoding, bytesPerSample) in formats) {
            val processor = HeadphonePeqAudioProcessor()
            processor.setProfile(listOf(PeqBand(1000f, 6f, 1f)), active = true, gate = testGate)
            processor.configure(AudioProcessor.AudioFormat(44100, 6, encoding))
            processor.flush()
            val frameCount = 16
            val input = ByteBuffer.allocateDirect(frameCount * 6 * bytesPerSample)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(frameCount * 6) {
                when (encoding) {
                    C.ENCODING_PCM_24BIT -> { input.put(0); input.put(0); input.put(0) }
                    C.ENCODING_PCM_32BIT -> input.putInt(0)
                    else -> input.putFloat(0f)
                }
            }
            input.flip()
            processor.queueInput(input)
            assertEquals(frameCount * 6 * bytesPerSample, processor.output.remaining())
        }
    }
}
