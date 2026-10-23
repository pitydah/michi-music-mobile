package org.michimusic.mobile.ui.screens.devices

import org.michimusic.core.models.UnifiedDevice
import org.michimusic.mobile.sync.DeviceAction

enum class DeviceDiscoveryStatus {
    NOT_SEEN,
    DISCOVERED,
    STALE
}

enum class DeviceTrustStatus {
    UNKNOWN,
    PENDING,
    TRUSTED,
    REVOKED,
    IDENTITY_MISMATCH
}

enum class DeviceConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    OFFLINE,
    ERROR
}

data class DeviceUiItem(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val deviceType: String,
    val discoveryStatus: DeviceDiscoveryStatus = DeviceDiscoveryStatus.DISCOVERED,
    val trustStatus: DeviceTrustStatus = DeviceTrustStatus.UNKNOWN,
    val connectionStatus: DeviceConnectionStatus = DeviceConnectionStatus.DISCONNECTED,
    val capabilities: Set<String> = emptySet(),
    val primaryAction: DeviceAction? = null,
    val secondaryActions: List<DeviceAction> = emptyList(),
    val underlyingDevice: UnifiedDevice? = null,
) {
    companion object {
        fun fromUnifiedDevice(
            device: UnifiedDevice,
            isConnecting: Boolean = false,
            isDiscovered: Boolean = true,
        ): DeviceUiItem {
            val actions = org.michimusic.mobile.sync.DeviceActionResolver.resolveActions(device, isConnecting)
            val primary = actions.firstOrNull { it.isPrimary }
            val secondaries = actions.filterNot { it.isPrimary }

            val connStatus = when (device.connectionState) {
                org.michimusic.core.models.SyncConnectionState.CONNECTED -> DeviceConnectionStatus.CONNECTED
                org.michimusic.core.models.SyncConnectionState.CONNECTING,
                org.michimusic.core.models.SyncConnectionState.DISCOVERING,
                org.michimusic.core.models.SyncConnectionState.PAIRING -> DeviceConnectionStatus.CONNECTING
                org.michimusic.core.models.SyncConnectionState.OFFLINE -> DeviceConnectionStatus.OFFLINE
                org.michimusic.core.models.SyncConnectionState.ERROR -> DeviceConnectionStatus.ERROR
                else -> DeviceConnectionStatus.DISCONNECTED
            }

            val trust = when {
                device.isRevoked -> DeviceTrustStatus.REVOKED
                device.isPaired -> DeviceTrustStatus.TRUSTED
                device.connectionState == org.michimusic.core.models.SyncConnectionState.PAIRING -> DeviceTrustStatus.PENDING
                else -> DeviceTrustStatus.UNKNOWN
            }

            val caps = mutableSetOf<String>()
            val resolvedCaps = org.michimusic.link.capability.CapabilityResolver.resolve(device.roles, device.features)
            if (resolvedCaps.isLibraryHost) caps.add("Biblioteca")
            if (resolvedCaps.isSyncHost) caps.add("Sincronización")
            if (resolvedCaps.isPlaybackHost) caps.add("Control Remoto")
            if (resolvedCaps.isAudioReceiver) caps.add("Receptor Stream")

            return DeviceUiItem(
                id = device.id,
                name = device.name.ifEmpty { "Michi Node" },
                ip = device.ip,
                port = device.port,
                deviceType = device.deviceType,
                discoveryStatus = if (isDiscovered) DeviceDiscoveryStatus.DISCOVERED else DeviceDiscoveryStatus.NOT_SEEN,
                trustStatus = trust,
                connectionStatus = connStatus,
                capabilities = caps,
                primaryAction = primary,
                secondaryActions = secondaries,
                underlyingDevice = device,
            )
        }
    }
}
