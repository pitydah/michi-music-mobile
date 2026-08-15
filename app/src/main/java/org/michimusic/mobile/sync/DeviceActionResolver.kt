package org.michimusic.mobile.sync

import org.michimusic.core.models.UnifiedDevice
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
        device: UnifiedDevice,
        isConnecting: Boolean,
    ): List<DeviceAction> {
        val actions = mutableListOf<DeviceAction>()

        if (device.connectionState != SyncConnectionState.CONNECTED) {
            actions.add(
                DeviceAction(
                    type = DeviceActionType.CONNECT,
                    label = if (isConnecting) "Conectando..." else if (device.isPaired) "Conectar" else "Vincular",
                    isPrimary = true,
                )
            )
            return actions
        }

        // Capabilities / Role driven actions for connected device
        val roles = device.roles
        val isServer = roles.contains("server")
        val isPlayer = roles.contains("player")
        val isReceiver = roles.contains("audio_receiver") || roles.contains("video_receiver") || roles.contains("cast_receiver")

        if (isServer) {
            actions.add(DeviceAction(DeviceActionType.BROWSE_LIBRARY, "Biblioteca", isPrimary = true))
            actions.add(DeviceAction(DeviceActionType.SYNC_LIBRARY, "Sincronizar"))
        }
        
        if (isPlayer) {
            actions.add(DeviceAction(DeviceActionType.CONTROL_PLAYBACK, "Controlar", isPrimary = !isServer))
            actions.add(DeviceAction(DeviceActionType.CONTINUE_PLAYBACK_HERE, "Continuar aquí"))
        } else if (isReceiver) {
            actions.add(DeviceAction(DeviceActionType.PLAY_ON_DEVICE, "Reproducir aquí", isPrimary = !isServer))
        }

        // Fallback if no roles matched but connected
        if (actions.isEmpty()) {
            actions.add(DeviceAction(DeviceActionType.CONTROL_PLAYBACK, "Controlar", isPrimary = true))
        }

        actions.add(DeviceAction(DeviceActionType.DISCONNECT, "Desconectar", isDestructive = true))
        return actions
    }
}
