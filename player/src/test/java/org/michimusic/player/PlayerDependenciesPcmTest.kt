package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlayerDependenciesPcmTest {
    private fun configuredTap(): RtpPcmAudioTap = RtpPcmAudioTap().apply {
        configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        PlayerDependencies.rtpPcmAudioTap = this
    }

    @Test fun `streaming callback leaves Media3 thread and local PCM stays muted`() {
        val tap = configuredTap()
        val received = CountDownLatch(1)
        var workerName = ""
        try {
            PlayerDependencies.startPcmStreaming { workerName = Thread.currentThread().name; received.countDown() }
            tap.queueInput(ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40)))
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertEquals("Michi-PCM-Network", workerName)
            val output = tap.output
            val samples = ByteArray(output.remaining()); output.get(samples)
            assertTrue(samples.contentEquals(byteArrayOf(0, 0, 0, 0)))
        } finally {
            PlayerDependencies.stopPcmStreaming(); PlayerDependencies.rtpPcmAudioTap = null
        }
    }

    @Test fun `backpressure faults remote session and retains local silence`() {
        val tap = configuredTap()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            PlayerDependencies.startPcmStreaming { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            tap.queueInput(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(12) { tap.queueInput(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))) }
            assertTrue(tap.hasDeliveryFault)
            assertTrue(tap.isPaused)
            assertTrue(tap.muteLocalOutput)
            PlayerDependencies.resumePcmStreaming()
            assertTrue("No implicit resume after a failed transport", tap.isPaused)
        } finally {
            release.countDown(); PlayerDependencies.stopPcmStreaming(); PlayerDependencies.rtpPcmAudioTap = null
        }
    }

    @Test fun `explicit stop restores audio and never reports stale worker fault`() {
        val tap = configuredTap()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            PlayerDependencies.startPcmStreaming { started.countDown(); release.await(3, TimeUnit.SECONDS); throw IllegalStateException("old session") }
            tap.queueInput(ByteBuffer.wrap(byteArrayOf(9, 8, 7, 6)))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val replacement = CountDownLatch(1)
            PlayerDependencies.startPcmStreaming { replacement.countDown() }
            release.countDown()
            tap.queueInput(ByteBuffer.wrap(byteArrayOf(2, 2, 2, 2)))
            assertTrue(replacement.await(2, TimeUnit.SECONDS))
            assertFalse("Stale worker may not fail replacement", tap.hasDeliveryFault)
            PlayerDependencies.stopPcmStreaming()
            assertFalse(tap.muteLocalOutput)
            tap.queueInput(ByteBuffer.wrap(byteArrayOf(5, 5, 5, 5)))
            val output = tap.output; val raw = ByteArray(output.remaining()); output.get(raw)
            assertTrue(raw.contentEquals(byteArrayOf(5, 5, 5, 5)))
        } finally {
            release.countDown(); PlayerDependencies.stopPcmStreaming(); PlayerDependencies.rtpPcmAudioTap = null
        }
    }
}
