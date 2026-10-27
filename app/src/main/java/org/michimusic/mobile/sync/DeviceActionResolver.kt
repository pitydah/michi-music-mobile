package org.michimusic.mobile.sync

import org.michimusic.core.models.UnifiedDevice
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.capability.CapabilityResolver

enum class DeviceActionType {
    CONNECT,
    DISCONNECT,
    SYNC_LIBRARY,
    AIRCAT_TRANSFER,
    BROWSE_LIBRARY,
    CONTROL_PLAYBACK,
    CONTINUE_PLAYBACK_HERE,
    PLAY_ON_DEVICE,
    VIEW_DETAILS,
}

data class DeviceAction(
    val type: DeviceActionType,
    val label: String,
    val isPrimary: Boolean = false,
    val isDestructive: Boolean = false,
)

object DeviceActionResolver {
    /** AirCat transfers are a Michi Devices capability, not a generic sync-host
     * feature. Never show this action unless the peer advertises it explicitly. */
    fun supportsAirCat(device: UnifiedDevice): Boolean = device.features.any { feature ->
        val key = feature.trim().lowercase().replace('-', '_')
        key == "aircat" || key == "aircat_transfer" ||
            key == "devices_aircat" || key == "michi_devices_aircat" ||
            key == "devices.aircat"
    }

    /**
     * Resolves actions ensuring EXACTLY ONE primary action (isPrimary == true).
     *
     * Order of precedence for primary action when CONNECTED:
     * 1. Library Host -> BROWSE_LIBRARY ("Biblioteca")
     * 2. Playback Host (e.g. desktop player) -> CONTROL_PLAYBACK ("Controlar")
     * 3. Audio Receiver (e.g. Stream receiver) -> PLAY_ON_DEVICE ("Reproducir aquí")
     * 4. Sync Host only -> SYNC_LIBRARY ("Sincronizar")
     * 5. Fallback -> VIEW_DETAILS ("Detalles")
     *
     * When NOT connected:
     * - If revoked: VIEW_DETAILS is primary, no connect action.
     * - Otherwise: CONNECT is primary ("Conectando..." / "Conectar" / "Vincular").
     */
    fun resolveActions(
        device: UnifiedDevice,
        isConnecting: Boolean,
    ): List<DeviceAction> {
        val actions = mutableListOf<DeviceAction>()

        if (device.isRevoked) {
            actions.add(DeviceAction(DeviceActionType.VIEW_DETAILS, "Detalles", isPrimary = true))
            return actions
        }

        if (device.connectionState != SyncConnectionState.CONNECTED) {
            val label = when {
                isConnecting -> "Conectando..."
                device.isPaired -> "Conectar"
                else -> "Vincular"
            }
            actions.add(
                DeviceAction(
                    type = DeviceActionType.CONNECT,
                    label = label,
                    isPrimary = true,
                )
            )
            return actions
        }

        // Single source of truth: resolve typed capabilities
        val caps = CapabilityResolver.resolve(
            roles = device.roles,
            features = device.features
        )

        // Determine primary action candidate
        val primaryType = when {
            caps.isLibraryHost -> DeviceActionType.BROWSE_LIBRARY
            caps.isPlaybackHost -> DeviceActionType.CONTROL_PLAYBACK
            caps.isAudioReceiver -> DeviceActionType.PLAY_ON_DEVICE
            caps.isSyncHost -> DeviceActionType.SYNC_LIBRARY
            else -> DeviceActionType.VIEW_DETAILS
        }

        if (caps.isLibraryHost) {
            actions.add(DeviceAction(DeviceActionType.BROWSE_LIBRARY, "Biblioteca", isPrimary = (primaryType == DeviceActionType.BROWSE_LIBRARY)))
        }
        if (caps.isSyncHost) {
            actions.add(DeviceAction(DeviceActionType.SYNC_LIBRARY, "Sincronizar", isPrimary = (primaryType == DeviceActionType.SYNC_LIBRARY)))
            if (supportsAirCat(device)) {
                actions.add(DeviceAction(DeviceActionType.AIRCAT_TRANSFER, "AirCat", isPrimary = false))
            }
        }
        if (caps.isPlaybackHost) {
            actions.add(DeviceAction(DeviceActionType.CONTROL_PLAYBACK, "Controlar", isPrimary = (primaryType == DeviceActionType.CONTROL_PLAYBACK)))
            actions.add(DeviceAction(DeviceActionType.CONTINUE_PLAYBACK_HERE, "Continuar aquí", isPrimary = false))
        }
        if (caps.isAudioReceiver) {
            actions.add(DeviceAction(DeviceActionType.PLAY_ON_DEVICE, "Reproducir aquí", isPrimary = (primaryType == DeviceActionType.PLAY_ON_DEVICE)))
        }

        // Fallback if no specific functional actions were resolved
        if (actions.isEmpty()) {
            actions.add(DeviceAction(DeviceActionType.VIEW_DETAILS, "Detalles", isPrimary = true))
        }

        actions.add(DeviceAction(DeviceActionType.DISCONNECT, "Desconectar", isPrimary = false, isDestructive = true))

        // Invariant check: Ensure exactly one action is primary
        val primaryCount = actions.count { it.isPrimary }
        if (primaryCount == 0) {
            val first = actions.first()
            actions[0] = first.copy(isPrimary = true)
        } else if (primaryCount > 1) {
            var foundFirst = false
            for (i in actions.indices) {
                if (actions[i].isPrimary) {
                    if (foundFirst) {
                        actions[i] = actions[i].copy(isPrimary = false)
                    } else {
                        foundFirst = true
                    }
                }
            }
        }

        return actions
    }
}
