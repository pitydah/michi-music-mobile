package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.UnifiedDevice

class AirCatEligibilityPolicyTest {

    private fun createDevice(
        id: String = "dev_test",
        name: String = "Test Device",
        isPaired: Boolean = true,
        isRevoked: Boolean = false,
        deviceType: String = "SERVER",
        roles: List<String> = listOf("LIBRARY", "SYNC"),
        features: List<String> = listOf("aircat"),
        connectionState: SyncConnectionState = SyncConnectionState.CONNECTED,
    ) = UnifiedDevice(
        id = id,
        name = name,
        ip = "192.168.1.50",
        port = 8400,
        connectionState = connectionState,
        isPaired = isPaired,
        deviceType = deviceType,
        roles = roles,
        features = features,
        isRevoked = isRevoked,
    )

    @Test
    fun isAirCatEligible_whenDeviceIsValidPairedServer_returnsTrue() {
        val device = createDevice()
        assertTrue("Valid paired server must be eligible for AirCat", AirCatEligibilityPolicy.isAirCatEligible(device))
    }

    @Test
    fun isAirCatEligible_whenIdIsBlank_returnsFalse() {
        val device = createDevice(id = "   ")
        assertFalse("Device with blank ID must be rejected", AirCatEligibilityPolicy.isAirCatEligible(device))
    }

    @Test
    fun isAirCatEligible_whenDeviceIsRevoked_returnsFalse() {
        val device = createDevice(isRevoked = true)
        assertFalse("Revoked device must never be eligible for AirCat", AirCatEligibilityPolicy.isAirCatEligible(device))
    }

    @Test
    fun isAirCatEligible_whenDeviceIsNotPaired_returnsFalse() {
        val device = createDevice(isPaired = false)
        assertFalse("Unpaired device without stored trust must not be eligible for AirCat", AirCatEligibilityPolicy.isAirCatEligible(device))
    }

    @Test
    fun isAirCatEligible_whenDeviceIsMichiStream_returnsFalse() {
        val streamDevice = createDevice(deviceType = "michi_stream", roles = listOf("RECEIVER"))
        assertFalse("Michi Stream receiver must be ineligible for AirCat file transfers", AirCatEligibilityPolicy.isAirCatEligible(streamDevice))

        val streamVariant = createDevice(deviceType = "MICHI-MUSIC-STREAM-PRO")
        assertFalse("Michi Stream variants must be ineligible for AirCat", AirCatEligibilityPolicy.isAirCatEligible(streamVariant))
    }

    @Test
    fun isAirCatEligible_whenDeviceIsUsbDacOrDap_returnsFalse() {
        val dacDevice = createDevice(deviceType = "USB_DAC", roles = listOf("DAC"))
        assertFalse("USB DAC must be ineligible for AirCat", AirCatEligibilityPolicy.isAirCatEligible(dacDevice))

        val dapDevice = createDevice(deviceType = "DAP", roles = listOf("AUDIO_RECEIVER"))
        assertFalse("Passive DAP must be ineligible for AirCat", AirCatEligibilityPolicy.isAirCatEligible(dapDevice))
    }

    @Test
    fun isAirCatEligible_whenDeviceIsPureReceiverRole_returnsFalse() {
        val receiver = createDevice(deviceType = "CUSTOM", roles = listOf("RECEIVER"), features = emptyList())
        assertFalse("Pure receiver role without sync/storage capabilities must be ineligible", AirCatEligibilityPolicy.isAirCatEligible(receiver))
    }

    @Test
    fun isAirCatEligible_whenDeviceHasExplicitAirCatFeature_returnsTrue() {
        val phone = createDevice(deviceType = "PHONE", roles = emptyList(), features = listOf("devices_aircat"))
        assertTrue("Device with explicit AirCat feature must be eligible", AirCatEligibilityPolicy.isAirCatEligible(phone))
    }

    @Test
    fun isAirCatEligible_whenDesktopOrServerLacksAirCatCapability_returnsFalse() {
        val serverWithoutAirCat = createDevice(
            deviceType = "SERVER",
            roles = listOf("LIBRARY", "PLAYBACK"),
            features = emptyList() // No AirCat!
        )
        assertFalse("Server without announced AirCat capability must be ineligible", AirCatEligibilityPolicy.isAirCatEligible(serverWithoutAirCat))

        val desktopWithoutAirCat = createDevice(
            deviceType = "DESKTOP",
            roles = listOf("CONTROL"),
            features = listOf("remote_control") // No AirCat!
        )
        assertFalse("Desktop without announced AirCat capability must be ineligible", AirCatEligibilityPolicy.isAirCatEligible(desktopWithoutAirCat))
    }

    @Test
    fun isAirCatEligible_whenDeviceDeclaresAirCatInRoles_returnsTrue() {
        val node = createDevice(
            deviceType = "CUSTOM_NODE",
            roles = listOf("AIRCAT_RECEIVER"),
            features = emptyList()
        )
        assertTrue("Device declaring aircat in roles must be eligible", AirCatEligibilityPolicy.isAirCatEligible(node))
    }

    @Test
    fun filterEligibleDevices_filtersCorrectly() {
        val eligible1 = createDevice(id = "srv_1", deviceType = "SERVER", features = listOf("aircat"))
        val eligible2 = createDevice(id = "desk_1", deviceType = "DESKTOP", features = listOf("devices_aircat"))
        val ineligibleStream = createDevice(id = "stream_1", deviceType = "michi_stream")
        val ineligibleNoAirCat = createDevice(id = "srv_no_aircat", deviceType = "SERVER", features = emptyList())
        val ineligibleRevoked = createDevice(id = "rev_1", isRevoked = true)
        val ineligibleUnpaired = createDevice(id = "unp_1", isPaired = false)

        val list = listOf(eligible1, ineligibleStream, eligible2, ineligibleNoAirCat, ineligibleRevoked, ineligibleUnpaired)
        val filtered = AirCatEligibilityPolicy.filterEligibleDevices(list)

        assertEquals(2, filtered.size)
        assertEquals(listOf("srv_1", "desk_1"), filtered.map { it.id })
    }
}
