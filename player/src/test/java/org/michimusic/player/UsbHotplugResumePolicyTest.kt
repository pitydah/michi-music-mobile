package org.michimusic.player

import android.media.AudioDeviceInfo
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHotplugResumePolicyTest {
    @Test fun aMedia3ReasonNeverSubstitutesForNativeSpeakerPresence() {
        assertTrue(UsbHotplugResumePolicy.blockUnapprovedPlay(true, true))
        assertFalse(UsbHotplugResumePolicy.blockUnapprovedPlay(false, true))
        assertFalse(UsbHotplugResumePolicy.blockUnapprovedPlay(true, false))
        assertFalse(UsbHotplugResumePolicy.nativeSpeakerMatches(7, null, null))
        assertFalse(UsbHotplugResumePolicy.nativeSpeakerMatches(7, 7, AudioDeviceInfo.TYPE_USB_DEVICE))
        assertFalse(UsbHotplugResumePolicy.nativeSpeakerMatches(7, 8, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(UsbHotplugResumePolicy.nativeSpeakerMatches(7, 7, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test fun afterRecoveryASecondOutputIsNeverConfirmedAsTheSpeaker() {
        val speaker = OutputDescriptor(7, "Phone speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val otherDac = OutputDescriptor(202, "USB B", AudioDeviceInfo.TYPE_USB_DEVICE)
        val mismatch = AudioRouteStatus.RoutedElsewhere(speaker, otherDac, 1234L)
        assertTrue(UsbHotplugResumePolicy.recoveredSpeakerMismatch(mismatch, 7))
        assertFalse(UsbHotplugResumePolicy.recoveredSpeakerMismatch(mismatch, null))
        assertFalse(UsbHotplugResumePolicy.recoveredSpeakerMismatch(AudioRouteStatus.Requested(speaker), 7))
    }

    @Test fun recoveryNeverChoosesAnotherAttachedDac() {
        val dac = OutputDescriptor(11, "Other DAC", AudioDeviceInfo.TYPE_USB_DEVICE)
        val speaker = OutputDescriptor(2, "Phone", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertEquals(speaker, UsbHotplugResumePolicy.safePhoneSpeaker(listOf(dac, speaker)))
        assertNull(UsbHotplugResumePolicy.safePhoneSpeaker(listOf(dac)))
    }

    @Test fun idleWithRetainedItemIsPreparedButEmptyQueueIsNot() {
        assertTrue(UsbHotplugResumePolicy.needsPrepare(Player.STATE_IDLE, 1))
        assertFalse(UsbHotplugResumePolicy.needsPrepare(Player.STATE_IDLE, 0))
        assertFalse(UsbHotplugResumePolicy.needsPrepare(Player.STATE_READY, 1))
    }

    @Test fun audioTrackFailureCannotTriggerTrackSkip() {
        assertTrue(UsbHotplugResumePolicy.isOutputFailure(5001))
        assertTrue(UsbHotplugResumePolicy.isOutputFailure(5002))
        assertFalse(UsbHotplugResumePolicy.isOutputFailure(4001))
        assertFalse(UsbHotplugResumePolicy.isOutputFailure(2000))
    }
}
