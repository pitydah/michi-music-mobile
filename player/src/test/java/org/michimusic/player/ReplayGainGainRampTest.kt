package org.michimusic.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainGainRampTest {
    @Test fun initialFirstBufferHasNoArtificialFade() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(0.3f, 48000)
        assertEquals(0.3f, ramp.nextFrameGain(), 0.000001f)
        assertEquals(0, ramp.framesPending)
    }
    @Test fun fadesInTenMillisecondsAt44100() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(0.25f, 44100)
        ramp.prepare(1f, 44100)
        assertEquals(441, ramp.framesPending)
        val first = ramp.nextFrameGain()
        assertTrue(first > 0.25f && first < 1f)
        repeat(440) { ramp.nextFrameGain() }
        assertEquals(1f, ramp.nextFrameGain(), 0.000001f)
        assertTrue(ramp.isSteadyUnity)
    }
    @Test fun changesAgainMidRampStartFromCurrentNotPreviousTarget() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(0.2f, 48000)
        ramp.prepare(1f, 48000)
        repeat(100) { ramp.nextFrameGain() }
        val mid = ramp.renderedGain
        ramp.prepare(0.4f, 48000)
        val next = ramp.nextFrameGain()
        assertTrue(next > 0.2f)
        assertTrue(kotlin.math.abs(next-mid) < 0.01f)
        repeat(479) { ramp.nextFrameGain() }
        assertEquals(0.4f, ramp.renderedGain, 0.000001f)
    }
    @Test fun multiChannelOnlyAdvancesWhenCalledForFullFrame() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(0f, 48000)
        ramp.prepare(1f, 48000)
        val oneFrame = ramp.nextFrameGain()
        assertEquals(1f/480f, oneFrame, 0.000001f)
        assertEquals(oneFrame, ramp.renderedGain, 0.000001f)
        assertEquals(479, ramp.framesPending)
    }
    @Test fun transparentBypassFadesToUnity() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(0.3f, 96000)
        ramp.prepare(1f, 96000)
        assertTrue(!ramp.isSteadyUnity)
        repeat(960) { ramp.nextFrameGain() }
        assertTrue(ramp.isSteadyUnity)
    }
    @Test fun nonFiniteTargetsFallbackToUnity() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(Float.NaN, 44100)
        assertEquals(1f, ramp.nextFrameGain(), 0f)
        ramp.prepare(Float.POSITIVE_INFINITY, 44100)
        assertTrue(ramp.isSteadyUnity)
        ramp.prepare(-2f, 44100)
        assertTrue(ramp.isSteadyUnity)
    }
    @Test fun unityCeilingIsFailClosedEvenDuringBoost() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(3f, 48000)
        ramp.prepare(2f, 48000)
        assertTrue(ramp.nextFrameGain() > 1f)
        ramp.prepare(1f, 48000, unityCeiling = true)
        assertTrue(ramp.renderedGain <= 1f)
        repeat(480) { assertTrue(ramp.nextFrameGain() <= 1f) }
    }
    @Test fun flushResetDoesNotCarryPreviousTrackGain() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(0.2f, 48000)
        ramp.prepare(1f, 48000)
        ramp.nextFrameGain()
        ramp.reset()
        ramp.prepare(0.7f, 48000)
        assertEquals(0.7f, ramp.nextFrameGain(), 0.000001f)
    }
    @Test fun noRetargetWhenModeValueUnchanged() {
        val ramp = ReplayGainGainRamp()
        ramp.prepare(0.4f, 48000)
        ramp.prepare(0.8f, 48000)
        repeat(20) { ramp.nextFrameGain() }
        val pending = ramp.framesPending
        ramp.prepare(0.8f, 48000)
        assertEquals(pending, ramp.framesPending)
    }
}
