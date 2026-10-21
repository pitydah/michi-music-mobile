package org.michimusic.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.PairedDevice
import org.michimusic.link.identity.IdentityStatus

class SyncIdentitySeparationTest {

    @Test
    fun testLocalMichiIdDistinctFromRemoteServerId() {
        val localMichiId = "michi_id_local_phone_12345"
        val remoteServerId = "server_node_alpha_67890"

        val inputData = SyncWorker.buildInputData(
            baseUrl = "http://192.168.1.100:8400",
            deviceId = remoteServerId,
            alias = "Pixel 8",
            clientDeviceId = localMichiId,
        )

        assertEquals("Target deviceId must be the remote server ID", remoteServerId, inputData.getString("deviceId"))
        assertEquals("clientDeviceId must be the local client identity", localMichiId, inputData.getString("clientDeviceId"))
        assertNotEquals("Remote and local IDs must never be collapsed as identical", inputData.getString("deviceId"), inputData.getString("clientDeviceId"))
    }

    @Test
    fun testRevokedDeviceCheckInPairedDevice() {
        val activeDevice = PairedDevice(
            deviceId = "srv_active",
            deviceName = "Living Room Hub",
            deviceToken = "tok_active_abc",
            isRevoked = false,
        )

        val revokedDevice = PairedDevice(
            deviceId = "srv_revoked",
            deviceName = "Old Compromised Node",
            deviceToken = "tok_revoked_xyz",
            isRevoked = true,
        )

        assertFalse("Active device must not be revoked", activeDevice.isRevoked)
        assertTrue("Revoked device must be marked as revoked", revokedDevice.isRevoked)
    }

    @Test
    fun testTwoServersWithDistinctTokens() {
        val serverA = PairedDevice(
            deviceId = "srv_A",
            deviceName = "Server Alpha",
            deviceToken = "token_for_A_only",
            lastUrl = "http://192.168.1.50:8400",
        )
        val serverB = PairedDevice(
            deviceId = "srv_B",
            deviceName = "Server Beta",
            deviceToken = "token_for_B_only",
            lastUrl = "http://192.168.1.60:8400",
        )

        assertNotEquals("Tokens must be distinct across servers", serverA.deviceToken, serverB.deviceToken)
        assertNotEquals("Device IDs must be distinct", serverA.deviceId, serverB.deviceId)
    }

    @Test
    fun testIdentityStatusEnum() {
        assertEquals(3, IdentityStatus.values().size)
        assertTrue(IdentityStatus.values().contains(IdentityStatus.VALID))
        assertTrue(IdentityStatus.values().contains(IdentityStatus.RECOVERED_NEW_KEY))
        assertTrue(IdentityStatus.values().contains(IdentityStatus.UNRECOVERABLE))
    }
}
