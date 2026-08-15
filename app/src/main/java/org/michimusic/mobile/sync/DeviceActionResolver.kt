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

        // Capabilities / Role & Feature driven actions for connected device
        val roles = device.roles
        val features = device.features
        val isLibrary = roles.contains("library_host") || roles.contains("music_server") || features.contains("library")
        val isSync = roles.contains("sync_host") || features.contains("sync")
        val isPlayback = roles.contains("playback_host") || features.contains("playback") || features.contains("remote_control")
        val isReceiver = roles.contains("audio_receiver") || features.contains("audio_output") || features.contains("streaming")

        if (isLibrary) {
            actions.add(DeviceAction(DeviceActionType.BROWSE_LIBRARY, "Biblioteca", isPrimary = true))
        }
        if (isSync) {
            actions.add(DeviceAction(DeviceActionType.SYNC_LIBRARY, "Sincronizar"))
        }
        if (isPlayback) {
            actions.add(DeviceAction(DeviceActionType.CONTROL_PLAYBACK, "Controlar", isPrimary = !isLibrary))
            actions.add(DeviceAction(DeviceActionType.CONTINUE_PLAYBACK_HERE, "Continuar aquí"))
        }
        if (isReceiver) {
            actions.add(DeviceAction(DeviceActionType.PLAY_ON_DEVICE, "Reproducir aquí", isPrimary = !isLibrary && !isPlayback))
        }

        // Fallback if no specific actions were resolved
        if (actions.isEmpty()) {
            actions.add(DeviceAction(DeviceActionType.VIEW_DETAILS, "Detalles", isPrimary = true))
        }

        actions.add(DeviceAction(DeviceActionType.DISCONNECT, "Desconectar", isDestructive = true))
        return actions
    }
}
