package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Executes the real PCM PEQ processor; Android Media3 contract on real devices remains a separate gate. */
class HeadphonePeqReceiptIntegrationTest {
    private fun processor(gate: HeadphonePeqAudioProcessor.RouteGate) = HeadphonePeqAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        flush()
        setProfile(peqPresetBands("Warm"), true, gate)
    }
    private fun send(p: HeadphonePeqAudioProcessor, frames: Int = 128) {
        val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { input.putShort(12000) }
        input.flip()
        p.queueInput(input)
    }
    @Test fun noReceiptUntilRealBufferIsProcessed() {
        val p=processor(HeadphonePeqAudioProcessor.RouteGate { true })
        assertNull(p.currentRenderReceipt())
        send(p)
        assertEquals(128L,p.currentRenderReceipt()?.processedFrames)
    }
    @Test fun changingPresetInvalidatesOldReceiptUntilNewPcm() {
        val gate=HeadphonePeqAudioProcessor.RouteGate { true }
        val p=processor(gate)
        send(p)
        val first=p.currentRenderReceipt()!!
        p.setProfile(peqPresetBands("Voice"), true, gate)
        assertNull(p.currentRenderReceipt())
        send(p)
        val second=p.currentRenderReceipt()!!
        assertEquals(128L,second.processedFrames)
        assertEquals(first.revision+1L,second.revision)
    }
    @Test fun revokedRouteImmediatelyInvalidatesObservedReceipt() {
        var valid=true
        val gate=HeadphonePeqAudioProcessor.RouteGate { valid }
        val p=processor(gate)
        send(p)
        assertNotNull(p.currentRenderReceipt())
        valid=false
        assertNull(p.currentRenderReceipt())
    }
    @Test fun flushClearsReceipt() {
        val p=processor(HeadphonePeqAudioProcessor.RouteGate { true })
        send(p)
        assertNotNull(p.currentRenderReceipt())
        p.flush()
        assertNull(p.currentRenderReceipt())
    }
    @Test fun unchangedPresetDoesNotRevokeRevision() {
        val gate=HeadphonePeqAudioProcessor.RouteGate { true }
        val p=processor(gate)
        send(p)
        val before=p.currentRenderReceipt()!!
        p.setProfile(peqPresetBands("Warm"), true, gate)
        assertEquals(before.revision,p.currentRenderReceipt()?.revision)
    }
    @Test fun flatNeutralBypassesAndCannotClaimProcessing() {
        val gate=HeadphonePeqAudioProcessor.RouteGate { true }
        val p=processor(gate)
        send(p)
        p.setProfile(peqPresetBands("Neutral"), true, gate)
        send(p)
        assertNull(p.currentRenderReceipt())
    }
}
