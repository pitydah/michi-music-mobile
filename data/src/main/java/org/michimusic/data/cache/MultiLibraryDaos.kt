package org.michimusic.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM libraries ORDER BY createdAt ASC")
    fun observeAllLibraries(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM libraries WHERE libraryId = :libraryId")
    suspend fun getLibraryById(libraryId: String): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLibrary(library: LibraryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibraries(libraries: List<LibraryEntity>)

    @Query("UPDATE libraries SET name = :newName, updatedAt = :updatedAt WHERE libraryId = :libraryId")
    suspend fun renameLibrary(libraryId: String, newName: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM libraries WHERE libraryId = :libraryId")
    suspend fun deleteLibrary(libraryId: String)
}

@Dao
interface TrackIdentityDao {
    @Query("SELECT * FROM track_identities WHERE trackId = :trackId")
    suspend fun getTrackIdentity(trackId: String): TrackIdentityEntity?

    @Query("SELECT * FROM track_identities")
    fun observeAllIdentities(): Flow<List<TrackIdentityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentity(identity: TrackIdentityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentities(identities: List<TrackIdentityEntity>)

    @Query("DELETE FROM track_identities WHERE trackId = :trackId")
    suspend fun deleteIdentity(trackId: String)
}

@Dao
interface TrackLocationDao {
    @Query("SELECT * FROM track_locations WHERE trackId = :trackId")
    suspend fun getLocationsForTrack(trackId: String): List<TrackLocationEntity>

    @Query("SELECT * FROM track_locations WHERE trackId = :trackId")
    fun observeLocationsForTrack(trackId: String): Flow<List<TrackLocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: TrackLocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocations(locations: List<TrackLocationEntity>)

    @Query("DELETE FROM track_locations WHERE locationId = :locationId")
    suspend fun deleteLocation(locationId: String)

    @Query("DELETE FROM track_locations WHERE trackId = :trackId")
    suspend fun deleteLocationsForTrack(trackId: String)
}

@Dao
interface LibraryMembershipDao {
    @Query("SELECT * FROM library_tracks WHERE libraryId = :libraryId")
    fun observeTrackMemberships(libraryId: String): Flow<List<LibraryTrackCrossRef>>

    @Query("SELECT libraryId FROM library_tracks WHERE trackId = :trackId")
    suspend fun getLibrariesForTrack(trackId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMembership(crossRef: LibraryTrackCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMemberships(crossRefs: List<LibraryTrackCrossRef>)

    @Query("DELETE FROM library_tracks WHERE libraryId = :libraryId AND trackId = :trackId")
    suspend fun removeMembership(libraryId: String, trackId: String)
}

@Dao
interface NormalizedPlaylistDao {
    @Query("SELECT * FROM playlist_headers ORDER BY createdAt ASC")
    fun observePlaylistHeaders(): Flow<List<PlaylistHeaderEntity>>

    @Query("SELECT * FROM playlist_headers WHERE playlistId = :id")
    suspend fun getPlaylistHeader(id: String): PlaylistHeaderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHeader(header: PlaylistHeaderEntity)

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeEntries(playlistId: String): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getEntries(playlistId: String): List<PlaylistEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: PlaylistEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: String)

    @Query("DELETE FROM playlist_headers WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Transaction
    suspend fun replacePlaylistEntries(playlistId: String, trackIds: List<String>) {
        clearEntries(playlistId)
        val entries = trackIds.mapIndexed { index, trackId ->
            PlaylistEntryEntity(
                entryId = "${playlistId}_entry_${index}_${System.currentTimeMillis()}",
                playlistId = playlistId,
                trackId = trackId,
                position = index,
            )
        }
        insertEntries(entries)
    }
}

@Dao
interface SyncProfileDao {
    @Query("SELECT * FROM sync_profiles ORDER BY createdAt ASC")
    fun observeProfiles(): Flow<List<SyncProfileEntity>>

    @Query("SELECT * FROM sync_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): SyncProfileEntity?

    @Query("SELECT * FROM sync_profiles WHERE sourceLibraryId = :sourceId AND destinationLibraryId = :destId LIMIT 1")
    suspend fun getProfileForLibraries(sourceId: String, destId: String): SyncProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: SyncProfileEntity)

    @Query("DELETE FROM sync_profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)
}

@Dao
interface SyncManifestDao {
    @Query("SELECT * FROM sync_manifests WHERE profileId = :profileId ORDER BY version DESC LIMIT 1")
    suspend fun getLatestManifest(profileId: String): SyncManifestEntity?

    @Query("SELECT * FROM sync_manifests WHERE jobId = :jobId")
    suspend fun getManifestByJobId(jobId: String): SyncManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManifest(manifest: SyncManifestEntity)

    @Query("DELETE FROM sync_manifests WHERE jobId = :jobId")
    suspend fun deleteManifest(jobId: String)
}

