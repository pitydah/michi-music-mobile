package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirCatRemoteIdentityPolicyTest {
    @Test fun exactIdentityMatchesAreAcceptedButNotCryptographicallyProven() {
        assertNull(AirCatRemoteIdentityPolicy.error("remote", "server", "michi", "pub", "server", "michi", "pub"))
    }

    @Test fun missingOrMismatchedPinnedFieldsFailClosed() {
        assertTrue(AirCatRemoteIdentityPolicy.error("remote", "server", "michi", "pub", "", "michi", "pub")!!.contains("serverId"))
        assertTrue(AirCatRemoteIdentityPolicy.error("remote", "server", "michi", "pub", "server", "different", "pub")!!.contains("MichiId"))
        assertTrue(AirCatRemoteIdentityPolicy.error("remote", "server", "michi", "pub", "server", "michi", "different")!!.contains("clave pública"))
        assertTrue(AirCatRemoteIdentityPolicy.error("remote", "server", "michi", "pub", "server", "michi", "")!!.contains("no provista"))
    }
}
