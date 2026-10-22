package org.michimusic.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.LibraryType
import org.michimusic.core.models.LocationAvailability
import org.michimusic.core.models.MusicLibrary
import org.michimusic.core.models.PlayableLocation
import org.michimusic.core.models.ResolvedTrack
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackIdentity
import org.michimusic.core.models.TrackLocation
import org.michimusic.core.models.TrackLocationType
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.LibraryDao
import org.michimusic.data.cache.LibraryEntity
import org.michimusic.data.cache.LibraryMembershipDao
import org.michimusic.data.cache.LibraryTrackCrossRef
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.TrackIdentityDao
import org.michimusic.data.cache.TrackIdentityEntity
import org.michimusic.data.cache.TrackLocationDao
import org.michimusic.data.cache.TrackLocationEntity
import java.util.UUID

interface MusicLibraryRepository {
    fun observeLibraries(): Flow<List<MusicLibrary>>
    fun observeTracks(scope: LibraryScope): Flow<List<ResolvedTrack>>
    suspend fun createUserLibrary(name: String): MusicLibrary
    suspend fun renameLibrary(id: String, newName: String): Result<Unit>
    suspend fun deleteUserLibrary(id: String): Result<Unit>
    suspend fun addTrackToLibrary(libraryId: String, trackId: String): Result<Unit>
    suspend fun removeMembership(libraryId: String, trackId: String): Result<Unit>
    suspend fun resolvePreferredLocation(trackId: String): PlayableLocation?
    suspend fun registerTrackWithLocation(
        identity: TrackIdentity,
        location: TrackLocation,
        libraryId: String = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID,
        origin: String = "LOCAL",
    ): Result<Unit>
}

class DefaultMusicLibraryRepository(
    private val libraryDao: LibraryDao,
    private val identityDao: TrackIdentityDao,
    private val locationDao: TrackLocationDao,
    private val membershipDao: LibraryMembershipDao,
) : MusicLibraryRepository {

    override fun observeLibraries(): Flow<List<MusicLibrary>> {
        return libraryDao.observeAllLibraries().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeTracks(scope: LibraryScope): Flow<List<ResolvedTrack>> {
        return combine(
            identityDao.observeAllIdentities(),
            when (scope) {
                is LibraryScope.All -> libraryDao.observeAllLibraries().map { emptyList() }
                is LibraryScope.One -> membershipDao.observeTrackMemberships(scope.libraryId)
            },
        ) { identities, memberships ->
            val allowedTrackIds = when (scope) {
                is LibraryScope.All -> null
                is LibraryScope.One -> memberships.map { it.trackId }.toSet()
            }

            identities
                .filter { allowedTrackIds == null || it.trackId in allowedTrackIds }
                .map { entity ->
                    val identity = entity.toDomain()
                    val locations = locationDao.getLocationsForTrack(entity.trackId).map { it.toDomain() }
                    val preferred = selectPreferredLocation(locations)
                    ResolvedTrack(
                        identity = identity,
                        locations = locations,
                        preferredLocation = preferred,
                    )
                }
        }
    }

    override suspend fun createUserLibrary(name: String): MusicLibrary {
        val id = "lib_custom_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val entity = LibraryEntity(
            libraryId = id,
            name = name,
            type = LibraryType.USER_CUSTOM.name,
            ownerId = null,
            isWritable = true,
            createdAt = now,
            updatedAt = now,
        )
        libraryDao.insertLibrary(entity)
        return entity.toDomain()
    }

    override suspend fun renameLibrary(id: String, newName: String): Result<Unit> = runCatching {
        val lib = libraryDao.getLibraryById(id) ?: error("Biblioteca no encontrada: $id")
        if (!lib.isWritable) error("La biblioteca predeterminada o del sistema no se puede renombrar")
        libraryDao.renameLibrary(id, newName)
    }

    override suspend fun deleteUserLibrary(id: String): Result<Unit> = runCatching {
        val lib = libraryDao.getLibraryById(id) ?: error("Biblioteca no encontrada: $id")
        if (!lib.isWritable || lib.libraryId == MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID) {
            error("No se puede eliminar la biblioteca predeterminada del dispositivo")
        }
        libraryDao.deleteLibrary(id)
    }

    override suspend fun addTrackToLibrary(libraryId: String, trackId: String): Result<Unit> = runCatching {
        membershipDao.addMembership(
            LibraryTrackCrossRef(
                libraryId = libraryId,
                trackId = trackId,
                addedAt = System.currentTimeMillis(),
                origin = "USER",
            )
        )
    }

    override suspend fun removeMembership(libraryId: String, trackId: String): Result<Unit> = runCatching {
        membershipDao.removeMembership(libraryId, trackId)
    }

    override suspend fun resolvePreferredLocation(trackId: String): PlayableLocation? {
        val locations = locationDao.getLocationsForTrack(trackId).map { it.toDomain() }
        return selectPreferredLocation(locations)
    }

    override suspend fun registerTrackWithLocation(
        identity: TrackIdentity,
        location: TrackLocation,
        libraryId: String,
        origin: String,
    ): Result<Unit> = runCatching {
        identityDao.insertIdentity(identity.toEntity())
        locationDao.insertLocation(location.toEntity())
        membershipDao.addMembership(
            LibraryTrackCrossRef(
                libraryId = libraryId,
                trackId = identity.id,
                addedAt = System.currentTimeMillis(),
                origin = origin,
            )
        )
    }

    private fun selectPreferredLocation(locations: List<TrackLocation>): PlayableLocation? {
        val availableLocations = locations.filter { loc ->
            val u = loc.uri
            if (loc.availability != LocationAvailability.AVAILABLE || u.isNullOrBlank()) return@filter false
            // Verify physical existence if it points to a local file path
            if (u.startsWith("/") || u.startsWith("file://")) {
                val path = if (u.startsWith("file://")) u.removePrefix("file://") else u
                java.io.File(path).exists()
            } else {
                true
            }
        }
        // Preference policy: SAF/MediaStore -> SYNCED_MANAGED -> REMOTE_URL
        val sorted = availableLocations.sortedBy { loc ->
            when (loc.type) {
                TrackLocationType.SAF -> 0
                TrackLocationType.MEDIASTORE -> 1
                TrackLocationType.SYNCED_MANAGED -> 2
                TrackLocationType.REMOTE_URL -> 3
            }
        }
        val top = sorted.firstOrNull() ?: return null
        return PlayableLocation(
            locationId = top.id,
            uri = top.uri ?: return null,
            type = top.type,
        )
    }

    private fun LibraryEntity.toDomain() = MusicLibrary(
        id = libraryId,
        name = name,
        type = runCatching { LibraryType.valueOf(type) }.getOrDefault(LibraryType.USER_CUSTOM),
        ownerId = ownerId,
        isWritable = isWritable,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun TrackIdentityEntity.toDomain() = TrackIdentity(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        albumTrackNumber = albumTrackNumber,
        contentHash = contentHash,
        identityScheme = identityScheme,
    )

    private fun TrackIdentity.toEntity() = TrackIdentityEntity(
        trackId = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        albumTrackNumber = albumTrackNumber,
        contentHash = contentHash,
        identityScheme = identityScheme,
    )

    private fun TrackLocationEntity.toDomain() = TrackLocation(
        id = locationId,
        trackId = trackId,
        type = runCatching { TrackLocationType.valueOf(kind) }.getOrDefault(TrackLocationType.MEDIASTORE),
        uri = uri,
        sourceDeviceId = sourceDeviceId,
        remoteTrackId = remoteTrackId,
        availability = runCatching { LocationAvailability.valueOf(availability) }.getOrDefault(LocationAvailability.AVAILABLE),
    )

    private fun TrackLocation.toEntity() = TrackLocationEntity(
        locationId = id,
        trackId = trackId,
        kind = type.name,
        uri = uri,
        sourceDeviceId = sourceDeviceId,
        remoteTrackId = remoteTrackId,
        availability = availability.name,
    )
}

object LegacyTrackMapper {
    fun toLegacyTrack(resolved: ResolvedTrack): Track {
        val prefLoc = resolved.preferredLocation
        val source = when (prefLoc?.type) {
            TrackLocationType.REMOTE_URL -> TrackSource.STREAMING
            TrackLocationType.SYNCED_MANAGED -> TrackSource.SYNCED
            else -> TrackSource.LOCAL
        }
        return Track(
            id = resolved.identity.id,
            title = resolved.identity.title,
            artist = resolved.identity.artist,
            album = resolved.identity.album,
            duration = resolved.identity.durationMs,
            filepath = prefLoc?.uri ?: "",
            source = source,
            contentHash = resolved.identity.contentHash ?: "",
        )
    }

    fun fromLegacyTrack(track: Track): Pair<TrackIdentity, TrackLocation> {
        val identity = TrackIdentity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.duration,
            contentHash = track.contentHash.ifEmpty { null },
        )
        val locationType = when {
            track.source == TrackSource.STREAMING || track.filepath.startsWith("http://") || track.filepath.startsWith("https://") -> TrackLocationType.REMOTE_URL
            track.source == TrackSource.SYNCED -> TrackLocationType.SYNCED_MANAGED
            track.filepath.startsWith("content://") -> TrackLocationType.MEDIASTORE
            else -> TrackLocationType.SAF
        }
        val location = TrackLocation(
            id = "loc_${track.id}",
            trackId = track.id,
            type = locationType,
            uri = track.filepath,
            remoteTrackId = if (locationType == TrackLocationType.REMOTE_URL) track.id else null,
            availability = LocationAvailability.AVAILABLE,
        )
        return identity to location
    }
}
