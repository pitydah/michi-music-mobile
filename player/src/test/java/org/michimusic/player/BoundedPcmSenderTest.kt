package org.michimusic.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedPcmSenderTest {
    @Test fun `callback runs on separate worker and never on caller thread`() {
        val called = CountDownLatch(1)
        var threadName = ""
        val sender = BoundedPcmSender(send = { threadName = Thread.currentThread().name; called.countDown() }, onFailure = { throw it })
        sender.start()
        sender.offer(byteArrayOf(1))
        assertTrue(called.await(2, TimeUnit.SECONDS))
        assertEquals("Michi-PCM-Network", threadName)
        sender.close()
    }

    @Test fun `blocked receiver does not stall producer`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sender = BoundedPcmSender(send = { entered.countDown(); release.await(3, TimeUnit.SECONDS) }, onFailure = { throw it }, capacity = 2)
        sender.start()
        try {
            sender.offer(byteArrayOf(1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val produced = CountDownLatch(1)
            val producer = Thread { sender.offer(byteArrayOf(2)); produced.countDown() }
            producer.start()
            assertTrue("Audio producer must not wait for network send", produced.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            sender.close()
        }
    }

    @Test fun `queue overflow latches exactly one failure and stops sending`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failures = AtomicInteger(0)
        val sent = AtomicInteger(0)
        val sender = BoundedPcmSender(send = {
            sent.incrementAndGet(); entered.countDown(); release.await(3, TimeUnit.SECONDS)
        }, onFailure = { failures.incrementAndGet() }, capacity = 1)
        sender.start()
        try {
            sender.offer(byteArrayOf(1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            sender.offer(byteArrayOf(2)) // one queued
            sender.offer(byteArrayOf(3)) // saturated -> fail closed
            sender.offer(byteArrayOf(4)) // ignored after fault
            assertEquals(1, failures.get())
            assertEquals(1, sent.get())
        } finally {
            release.countDown()
            sender.close()
        }
    }

    @Test fun `network exception is reported instead of killing audio producer`() {
        val failed = CountDownLatch(1)
        val failures = AtomicInteger(0)
        val sender = BoundedPcmSender(send = { throw IllegalStateException("unreachable") }, onFailure = { failures.incrementAndGet(); failed.countDown() })
        sender.start()
        sender.offer(byteArrayOf(1))
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        sender.offer(byteArrayOf(2))
        assertEquals(1, failures.get())
        sender.close()
    }

    @Test fun `discard pending removes samples from pre transition format`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = AtomicInteger(0)
        val sender = BoundedPcmSender(send = { sent.incrementAndGet(); entered.countDown(); release.await(2, TimeUnit.SECONDS) }, onFailure = { throw it }, capacity = 4)
        sender.start()
        try {
            sender.offer(byteArrayOf(1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            sender.offer(byteArrayOf(2))
            sender.offer(byteArrayOf(3))
            sender.discardPending()
            release.countDown()
            Thread.sleep(80)
            assertEquals(1, sent.get())
        } finally {
            release.countDown()
            sender.close()
        }
    }

    @Test fun `stop is idempotent`() {
        val sender = BoundedPcmSender(send = {}, onFailure = { throw it })
        sender.start()
        sender.close()
        sender.close()
        sender.offer(byteArrayOf(1))
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun `oversized PCM block fails without entering network callback`() {
        val failures = AtomicInteger(0)
        val sent = AtomicInteger(0)
        val sender = BoundedPcmSender(send = { sent.incrementAndGet() }, onFailure = { failures.incrementAndGet() }, capacity = 2, maxChunkBytes = 8)
        sender.start()
        try {
            sender.offer(ByteArray(9))
            assertEquals(1, failures.get())
            assertEquals(0, sent.get())
        } finally { sender.close() }
    }
}
