package org.michimusic.mobile.devices.aircat

import kotlinx.serialization.Serializable

enum class AirCatTransport {
    LAN_HTTP,
    USB_ACCESSORY,
}

enum class AirCatTransferState {
    PENDING_AUTHORIZATION,
    NEGOTIATING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    PAUSED,
    CANCELLED,
    FAILED,
}

@Serializable
data class AirCatCapabilities(
    val protocol: String = "AirCat",
    val version: String = "1.0.0",
    val supportedTransports: List<String> = listOf("LAN_HTTP"),
    val supportsResume: Boolean = true,
    val supportedChecksums: List<String> = listOf("SHA-256"),
    val maxConcurrentTransfers: Int = 4,
    val availableStorageBytes: Long = 0L,
)

@Serializable
data class TransferIntent(
    val transferId: String,
    val sourceMichiId: String,
    val destinationMichiId: String,
    val sourceLibraryId: String,
    val destinationLibraryId: String,
    val selectionScope: String = "ALBUMS",
    val manifestVersion: Int = 1,
    val requiredScopes: List<String> = listOf("content:read", "content:transfer"),
    val expiresAt: Long = System.currentTimeMillis() + 3600_000L,
)

@Serializable
data class AirCatManifestItem(
    val logicalTrackId: String,
    val remoteTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val format: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class AirCatManifest(
    val manifestVersion: Int = 1,
    val transferId: String,
    val sourceDeviceId: String,
    val totalTracks: Int,
    val totalBytes: Long,
    val items: List<AirCatManifestItem> = emptyList(),
)

data class AirCatProgressEvent(
    val transferId: String,
    val currentTrackIndex: Int,
    val totalTracks: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val state: AirCatTransferState,
    val activeTrackTitle: String = "",
    val error: String? = null,
    val checksumVerified: Boolean = false,
    val libraryCommitted: Boolean = false,
)

sealed class AirCatException(message: String) : Exception(message) {
    class UnauthorizedPeer(id: String) : AirCatException("Peer no autorizado en Home Trust: $id")
    class RevokedPeer(id: String) : AirCatException("Dispositivo revocado del ecosistema: $id")
    class IncompatibleVersion(source: String, dest: String) : AirCatException("Versión incompatible de protocolo: $source vs $dest")
    class InsufficientStorage(required: Long, available: Long) : AirCatException("Espacio de almacenamiento insuficiente: requiere $required B, disponibles $available B")
    class ManifestVersionMismatch(expected: Int, actual: Int) : AirCatException("Versión de manifiesto inválida: esperado $expected, actual $actual")
    class DuplicatedTransfer(id: String) : AirCatException("Transferencia duplicada: $id")
    class TransferExpired(expiresAt: Long, now: Long) : AirCatException("Transferencia expirada: vence $expiresAt, actual $now")
    class TransferIdMismatch(expected: String, actual: String) : AirCatException("ID de transferencia no coincide: esperado $expected, actual $actual")
    class SourceMismatch(expected: String, actual: String) : AirCatException("Origen no coincide con el manifiesto: esperado $expected, actual $actual")
    class InvalidManifestStructure(reason: String) : AirCatException("Estructura de manifiesto inválida: $reason")
    class CorruptedPayload(msg: String) : AirCatException(msg)
    class ProtocolError(msg: String) : AirCatException(msg)
}
