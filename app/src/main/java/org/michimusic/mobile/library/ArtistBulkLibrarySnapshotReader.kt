package org.michimusic.mobile.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.ResolvedTrack
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.mobile.artist.ArtistBulkReadSnapshot
import org.michimusic.mobile.artist.ArtistBulkSnapshotReader
import java.security.MessageDigest

/**
 * D19: real local witness, not a UI counter or a manufactured constant.
 * The library epoch invalidates A->B->A scope selections; the persistent root epoch
 * invalidates A->B->A SAF roots; the catalog revision covers root-authority rescans;
 * the library digest covers changes delivered by the authoritative repository.
 */
class ArtistBulkLibrarySnapshotReader(
    private val libraryId: String,
    private val scopeStore: LibraryScopeStore,
    private val rootAuthority: MusicRootAuthority,
    private val repository: MusicLibraryRepository,
) : ArtistBulkSnapshotReader {
    override suspend fun current(): ArtistBulkReadSnapshot {
        require(libraryId.isNotBlank() && libraryId != "SCOPE_ALL")
        val expectedScope = LibraryScope.One(libraryId)
        val scopeEpoch = scopeStore.currentSelectionEpoch()
        check(scopeStore.resolution.value.authority().authorizedScope() == expectedScope) {
            "The active library scope is not authoritative for this preview"
        }
        val beforeRoot = rootAuthority.currentReviewStamp()
        val beforeCatalog = rootAuthority.revision.value
        // Cold read through the repository: never trust a remembered mosaic card to
        // serve as a new membership authority. No provider HTTP calls occur here.
        val tracks = try {
            repository.observeTracks(expectedScope).first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        check(scopeStore.currentSelectionEpoch() == scopeEpoch &&
            scopeStore.resolution.value.authority().authorizedScope() == expectedScope &&
            rootAuthority.currentReviewStamp() == beforeRoot &&
            rootAuthority.revision.value == beforeCatalog
        ) { "Library, root or catalog changed while reading the preview" }
        return ArtistBulkReadSnapshot(
            libraryId = libraryId,
            sourceId = beforeRoot.sourceId,
            rootEpoch = beforeRoot.epoch,
            catalogRevision = beforeCatalog,
            libraryRevision = artistBulkMembershipDigest(tracks, scopeEpoch),
        )
    }
}

/**
 * Stable 63-bit diagnostic generation. Includes the selection epoch and all exact
 * track memberships and location state. It is NOT a cryptographic authorization;
 * the eventual worker must revalidate Room association ownership at commit time.
 */
internal fun artistBulkMembershipDigest(tracks: List<ResolvedTrack>, scopeEpoch: Long): Long {
    require(scopeEpoch >= 0L)
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val length = bytes.size
        digest.update(byteArrayOf(
            (length ushr 24).toByte(), (length ushr 16).toByte(),
            (length ushr 8).toByte(), length.toByte(),
        ))
        digest.update(bytes)
    }
    add(scopeEpoch.toString())
    add(tracks.size.toString())
    tracks.sortedWith(compareBy<ResolvedTrack> { it.identity.id }
        .thenBy { it.identity.artist }.thenBy { it.identity.album }).forEach { track ->
        val identity = track.identity
        add(identity.id)
        add(identity.title)
        add(identity.artist)
        add(identity.album)
        add(identity.durationMs.toString())
        add(identity.contentHash.orEmpty())
        add(identity.identityScheme)
        add(track.preferredLocation?.locationId.orEmpty())
        add(track.preferredLocation?.uri.orEmpty())
        add(track.locations.size.toString())
        track.locations.sortedBy { it.id }.forEach { location ->
            add(location.id)
            add(location.type.name)
            add(location.uri.orEmpty())
            add(location.availability.name)
            add(location.sourceDeviceId.orEmpty())
            add(location.remoteTrackId.orEmpty())
        }
    }
    val bytes = digest.digest()
    var revision = 0L
    for (index in 0 until 8) revision = (revision shl 8) or (bytes[index].toLong() and 255L)
    return revision and Long.MAX_VALUE
}
