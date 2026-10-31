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

        // Dedicated audio receiver roles without library/sync capabilities are ineligible
        if (device.roles.any {
                val r = it.trim().lowercase()
                r == "receiver" || r == "audio_receiver" || r == "dac" || r == "dap"
            } && device.roles.none {
                val r = it.trim().lowercase()
                r.contains("library") || r.contains("sync") || r.contains("aircat")
            }
        ) {
            return false
        }

        // Must support AirCat explicitly or be a library/sync capable node
        val hasExplicitAirCat = DeviceActionResolver.supportsAirCat(device) ||
            device.features.any { it.contains("aircat", ignoreCase = true) } ||
            device.roles.any { it.contains("aircat", ignoreCase = true) }

        val hasGeneralStorageSync = (normalizedType in listOf("server", "desktop", "phone", "tablet") ||
            device.roles.any { it.contains("library", ignoreCase = true) || it.contains("sync", ignoreCase = true) } ||
            device.features.any { it.contains("library", ignoreCase = true) || it.contains("sync", ignoreCase = true) })

        return hasExplicitAirCat || hasGeneralStorageSync
    }

    fun filterEligibleDevices(devices: List<UnifiedDevice>): List<UnifiedDevice> {
        return devices.filter { isAirCatEligible(it) }
    }
}
