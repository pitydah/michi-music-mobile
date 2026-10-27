package org.michimusic.mobile.ui.components

import org.junit.Assert.*
import org.junit.Test

class StreamProvisioningPolicyTest {
    @Test fun recognizesExplicitMichiReceiverTypes() {
        assertTrue(isProvisionedMichiStream("michi_stream_standard"))
        assertTrue(isProvisionedMichiStream("MICHI-STREAM-PRO"))
        assertTrue(isProvisionedMichiStream("michi_music_stream"))
    }

    @Test fun doesNotMisclassifyOtherDevicesOrUserAlias() {
        assertFalse(isProvisionedMichiStream("desktop"))
        assertFalse(isProvisionedMichiStream("generic_receiver"))
        assertFalse(isProvisionedMichiStream("streaming_server"))
        assertFalse(isProvisionedMichiStream("mobile"))
    }

    // M29 regression tests: capability honesty and Stream provisioning.
    @Test fun noPhysicalButtonForMichiStream() {
        assertTrue(refusesPhysicalButtonForMichiStream("michi_stream_standard", "", "RECEIVER_BUTTON"))
        assertTrue(refusesPhysicalButtonForMichiStream("stream", "", "receiver_button"))
        assertTrue(refusesPhysicalButtonForMichiStream("receiver", "michi_music_stream", "RECEIVER_BUTTON"))
    }

    @Test fun otherMethodsAndOtherReceiversNotBlocked() {
        assertFalse(refusesPhysicalButtonForMichiStream("michi_stream_standard", "", "ED25519_CHALLENGE"))
        assertFalse(refusesPhysicalButtonForMichiStream("generic_receiver", "", "RECEIVER_BUTTON"))
        assertFalse(refusesPhysicalButtonForMichiStream("michi_micro_server", "", "SERVER_CODE"))
    }
}
