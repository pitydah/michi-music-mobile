package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-03: the fallback poll used a flat three seconds for every situation, including a
 * paused session and a host that kept failing. These are the cadences it must use now.
 */
class RemotePollingPolicyTest {

    @Test
    fun `sse connected means no polling at all`() {
        assertNull(
            RemotePollingPolicy.nextDelayMs(
                isPlaying = true,
                consecutiveFailures = 0,
                sseConnected = true,
            ),
        )
    }

    @Test
    fun `playback polls at the minimum cadence`() {
        assertEquals(
            RemotePollingPolicy.PLAYING_INTERVAL_MS,
            RemotePollingPolicy.nextDelayMs(isPlaying = true, consecutiveFailures = 0, sseConnected = false),
        )
    }

    @Test
    fun `a paused session polls far less often`() {
        val paused = RemotePollingPolicy.nextDelayMs(isPlaying = false, consecutiveFailures = 0, sseConnected = false)!!
        assertTrue("paused cadence should exceed playing cadence", paused > RemotePollingPolicy.PLAYING_INTERVAL_MS)
    }

    @Test
    fun `failures back off instead of hammering a broken host`() {
        val first = RemotePollingPolicy.nextDelayMs(true, 1, false)!!
        val second = RemotePollingPolicy.nextDelayMs(true, 2, false)!!
        val third = RemotePollingPolicy.nextDelayMs(true, 3, false)!!
        assertTrue(first > RemotePollingPolicy.PLAYING_INTERVAL_MS)
        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test
    fun `backoff never exceeds the ceiling`() {
        for (failures in 0..10) {
            for (playing in listOf(true, false)) {
                val delay = RemotePollingPolicy.nextDelayMs(playing, failures, sseConnected = false, jitter = 0.2)!!
                assertTrue(
                    "delay $delay exceeded ceiling for failures=$failures playing=$playing",
                    delay <= RemotePollingPolicy.MAX_INTERVAL_MS,
                )
            }
        }
    }

    @Test
    fun `jitter stays inside the accepted band`() {
        val base = RemotePollingPolicy.PLAYING_INTERVAL_MS
        assertEquals(base, RemotePollingPolicy.nextDelayMs(true, 0, false, jitter = 0.0))
        assertEquals(base, RemotePollingPolicy.nextDelayMs(true, 0, false, jitter = -1.0))
        val jittered = RemotePollingPolicy.nextDelayMs(true, 0, false, jitter = 0.2)!!
        assertTrue(jittered >= base)
        assertTrue(jittered <= base + (base * 0.2).toLong())
    }

    @Test
    fun `a negative failure count is treated as healthy`() {
        assertEquals(
            RemotePollingPolicy.nextDelayMs(true, 0, false),
            RemotePollingPolicy.nextDelayMs(true, -5, false),
        )
    }
}
