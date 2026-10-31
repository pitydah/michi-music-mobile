package org.michimusic.mobile.devices.aircat

import org.michimusic.core.models.UnifiedDevice
import org.michimusic.mobile.sync.DeviceActionResolver
import org.michimusic.mobile.ui.components.isProvisionedMichiStream

/**
 * Domain policy enforcing strict device eligibility for AirCat file transfers (K48-04).
 *
 * Rules:
 * - Must possess a non-blank device ID.
 * - Must NOT be revoked.
 * - Must be trusted and paired (or active session).
 * - Must NOT be a streaming receiver (e.g. Michi Stream PCM/I2S receiver).
 * - Must NOT be a passive USB DAC/DAP without file storage.
 * - Must explicitly support AirCat transfer or be a library/sync-capable general platform (Server/Desktop/Phone).
 */
object AirCatEligibilityPolicy {

    fun isAirCatEligible(device: UnifiedDevice): Boolean {
        if (device.id.isBlank()) return false
        if (device.isRevoked) return false
        if (!device.isPaired) return false

        val normalizedType = device.deviceType.trim().lowercase().replace('-', '_')
        if (isProvisionedMichiStream(device.deviceType) ||
            normalizedType == "stream" ||
            normalizedType == "audio_receiver" ||
            normalizedType in listOf("usb_dac", "dac", "dap", "usb_audio")
        ) {
            return false
        }

        // Dedicated audio receiver roles without explicit aircat capability are ineligible
        if (device.roles.any {
                val r = it.trim().lowercase()
                r == "receiver" || r == "audio_receiver" || r == "dac" || r == "dap"
            } && device.roles.none {
                it.trim().lowercase().contains("aircat")
            }
        ) {
            return false
        }

        // K49-04: Must announce verified AirCat capability explicitly.
        // Being a desktop/server/phone alone does NOT guarantee AirCat file transfer capability.
        val hasVerifiedAirCat = DeviceActionResolver.supportsAirCat(device) ||
            device.features.any { it.contains("aircat", ignoreCase = true) } ||
            device.roles.any { it.contains("aircat", ignoreCase = true) }

        return hasVerifiedAirCat
    }

    fun filterEligibleDevices(devices: List<UnifiedDevice>): List<UnifiedDevice> {
        return devices.filter { isAirCatEligible(it) }
    }
}
