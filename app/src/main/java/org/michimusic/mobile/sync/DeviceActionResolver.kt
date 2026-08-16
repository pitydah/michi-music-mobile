package org.michimusic.mobile.sync

import org.michimusic.core.models.UnifiedDevice
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.capability.CapabilityResolver

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

        // Single source of truth: resolve typed capabilities
        val caps = CapabilityResolver.resolve(
            roles = device.roles,
            features = device.features
        )

        if (caps.isLibraryHost) {
            actions.add(DeviceAction(DeviceActionType.BROWSE_LIBRARY, "Biblioteca", isPrimary = true))
        }
        if (caps.isSyncHost) {
            actions.add(DeviceAction(DeviceActionType.SYNC_LIBRARY, "Sincronizar"))
        }
        if (caps.isPlaybackHost) {
            actions.add(DeviceAction(DeviceActionType.CONTROL_PLAYBACK, "Controlar", isPrimary = !caps.isLibraryHost))
            actions.add(DeviceAction(DeviceActionType.CONTINUE_PLAYBACK_HERE, "Continuar aquí"))
        }
        if (caps.isAudioReceiver) {
            actions.add(DeviceAction(DeviceActionType.PLAY_ON_DEVICE, "Reproducir aquí", isPrimary = !caps.isLibraryHost && !caps.isPlaybackHost))
        }

        // Fallback if no specific actions were resolved
        if (actions.isEmpty()) {
            actions.add(DeviceAction(DeviceActionType.VIEW_DETAILS, "Detalles", isPrimary = true))
        }

        actions.add(DeviceAction(DeviceActionType.DISCONNECT, "Desconectar", isDestructive = true))
        return actions
    }
}
