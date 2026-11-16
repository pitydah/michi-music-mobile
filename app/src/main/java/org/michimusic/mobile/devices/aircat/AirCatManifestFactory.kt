package org.michimusic.mobile.devices.aircat

import org.michimusic.link.dto.ManifestTrackDto

/** Data-only assembly: no Link identity, trust, HTTP, Room or Android dependency. */
internal object AirCatManifestFactory {
    data class Prepared(
        val intent: TransferIntent,
        val manifest: AirCatManifest,
    )

    fun prepare(
        sourceDeviceId: String,
        destinationMichiId: String,
        tracks: List<ManifestTrackDto>,
        nowMillis: Long,
    ): Prepared {
        require(sourceDeviceId.isNotBlank() && destinationMichiId.isNotBlank()) {
            "AirCat source and destination identities must be nonblank"
        }
        require(nowMillis > 0L) { "AirCat clock must be positive" }
        require(tracks.size in 1..10_000) { "AirCat batch must be bounded and nonempty" }
        require(tracks.all { it.format.isNotBlank() }) { "AirCat original format missing" }
        require(tracks.all { it.size > 0L }) { "AirCat file size must be positive" }
        require(tracks.all { it.trackId.isNotBlank() &&
            it.effectiveAirCatRemoteId.isNotBlank() && it.effectiveAirCatLogicalId.isNotBlank() }) {
            "AirCat local and source track IDs must be nonblank"
        }
        require(tracks.map { it.effectiveAirCatRemoteId }.distinct().size == tracks.size) {
            "AirCat manifest contains duplicate remote track IDs"
        }
        require(tracks.map { it.effectiveAirCatLogicalId }.distinct().size == tracks.size) {
            "AirCat manifest contains duplicate logical track IDs"
        }
        // New Devices transfers always require a real file digest. The legacy
        // hashless recovery path remains separate; never silently label it VERIFIED.
        require(tracks.all { it.checksum?.matches(Regex("[0-9a-fA-F]{64}")) == true }) {
            "AirCat requires SHA-256 for every newly requested file"
        }
        // A timestamp by itself aliases transfers started within the same millisecond.
        // UUID is a unique operation key, NOT a cryptographic Link identity.
        val transferId = "aircat_${nowMillis}_${java.util.UUID.randomUUID()}"
        val items = tracks.map { track ->
            AirCatManifestItem(
                logicalTrackId = track.effectiveAirCatLogicalId,
                remoteTrackId = track.effectiveAirCatRemoteId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                format = track.format,
                size = track.size,
                sha256 = track.checksum ?: "",
            )
        }
        val totalBytes = items.fold(0L) { acc, item -> Math.addExact(acc, item.size) }
        return Prepared(
            intent = TransferIntent(
                transferId = transferId,
                sourceMichiId = sourceDeviceId,
                destinationMichiId = destinationMichiId,
                sourceLibraryId = sourceDeviceId,
                destinationLibraryId = "lib_synced_$sourceDeviceId",
            ),
            manifest = AirCatManifest(
                manifestVersion = 1,
                transferId = transferId,
                sourceDeviceId = sourceDeviceId,
                totalTracks = items.size,
                totalBytes = totalBytes,
                items = items,
            ),
        )
    }
}
