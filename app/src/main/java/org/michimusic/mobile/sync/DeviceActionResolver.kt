package org.michimusic.mobile.sync

import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState

enum class DeviceActionType {
    CONNECT,
    DISCONNECT,
    SYNC_LIBRARY,
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
    fun resolveActions(
        peer: DiscoveredPeer,
        connectionState: SyncConnectionState,
        isPeerConnected: Boolean,
        isConnecting: Boolean,
    ): List<DeviceAction> {
        val actions = mutableListOf<DeviceAction>()

        if (!isPeerConnected) {
            actions.add(
                DeviceAction(
                    type = DeviceActionType.CONNECT,
                    label = if (isConnecting) "Conectando..." else "Conectar",
                    isPrimary = true,
                )
            )
            return actions
        }

        // Capabilities / Role driven actions for connected device
        val deviceType = peer.deviceType.lowercase()
        when (deviceType) {
            "server" -> {
                actions.add(DeviceAction(DeviceActionType.BROWSE_LIBRARY, "Biblioteca", isPrimary = true))
                actions.add(DeviceAction(DeviceActionType.SYNC_LIBRARY, "Sincronizar"))
            }
            "desktop", "player" -> {
                actions.add(DeviceAction(DeviceActionType.CONTROL_PLAYBACK, "Controlar", isPrimary = true))
                actions.add(DeviceAction(DeviceActionType.CONTINUE_PLAYBACK_HERE, "Continuar aquí"))
            }
            "stream", "receiver" -> {
                actions.add(DeviceAction(DeviceActionType.PLAY_ON_DEVICE, "Reproducir aquí", isPrimary = true))
            }
            else -> {
                actions.add(DeviceAction(DeviceActionType.CONTROL_PLAYBACK, "Controlar", isPrimary = true))
            }
        }

        actions.add(DeviceAction(DeviceActionType.DISCONNECT, "Desconectar", isDestructive = true))
        return actions
    }
}
