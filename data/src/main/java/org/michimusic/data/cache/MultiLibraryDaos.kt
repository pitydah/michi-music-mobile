package org.michimusic.data.cache


import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow


@Dao
interface LibraryDao {
    @Query("SELECT * FROM libraries ORDER BY createdAt ASC")
    fun observeAllLibraries(): Flow<List<LibraryEntity>>


    @Query("SELECT * FROM libraries WHERE libraryId = :libraryId")
    suspend fun getLibraryById(libraryId: String): LibraryEntity?


    // REPLACE deletes the parent row first and fires ON DELETE CASCADE on library_tracks.
    // Preserve its primary key in place whenever the library already exists.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibraryIfAbsent(library: LibraryEntity): Long


    @Update
    suspend fun updateLibrary(library: LibraryEntity): Int


    @Transaction
    suspend fun insertLibrary(library: LibraryEntity) {
        if (insertLibraryIfAbsent(library) == -1L) {
            check(updateLibrary(library) == 1) { "Library disappeared during upsert" }
        }
    }


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


    // Never REPLACE identities: SQLite deletes the old parent and cascades all
    // track_locations and library_tracks before inserting the new row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIdentityIfAbsent(identity: TrackIdentityEntity): Long


    @Update
    suspend fun updateIdentity(identity: TrackIdentityEntity): Int


    @Transaction
    suspend fun insertIdentity(identity: TrackIdentityEntity) {
        if (insertIdentityIfAbsent(identity) == -1L) {
            check(updateIdentity(identity) == 1) { "Track identity disappeared during upsert" }
        }
    }


    @Transaction
    suspend fun insertIdentities(identities: List<TrackIdentityEntity>) {
        identities.forEach { insertIdentity(it) }
    }


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


    @Query("SELECT * FROM track_locations WHERE uri = :uri LIMIT 1")
    suspend fun getLocationByUri(uri: String): TrackLocationEntity?


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


    @Transaction
    suspend fun registerTrackWithLocationAtomic(
        identityDao: TrackIdentityDao,
        locationDao: TrackLocationDao,
        identity: TrackIdentityEntity,
        location: TrackLocationEntity,
        crossRef: LibraryTrackCrossRef,
    ) {
        identityDao.insertIdentity(identity)
        locationDao.insertLocation(location)
        addMembership(crossRef)
    }
}


@Dao
interface NormalizedPlaylistDao {
    @Query("SELECT * FROM playlist_headers ORDER BY createdAt ASC")
    fun observePlaylistHeaders(): Flow<List<PlaylistHeaderEntity>>


    @Query("SELECT * FROM playlist_headers WHERE playlistId = :id")
    suspend fun getPlaylistHeader(id: String): PlaylistHeaderEntity?


    // REPLACE would cascade-delete every entry in an existing playlist.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeaderIfAbsent(header: PlaylistHeaderEntity): Long


    @Update
    suspend fun updateHeader(header: PlaylistHeaderEntity): Int


    @Transaction
    suspend fun insertHeader(header: PlaylistHeaderEntity) {
        if (insertHeaderIfAbsent(header) == -1L) {
            check(updateHeader(header) == 1) { "Playlist header disappeared during upsert" }
        }
    }


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


@Dao
interface TransferCheckpointDao {
    @Query("SELECT * FROM transfer_checkpoints WHERE transferKey = :key")
    suspend fun getCheckpoint(key: String): TransferCheckpointEntity?


    @Query("SELECT * FROM transfer_checkpoints WHERE trackId = :trackId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestCheckpointForTrack(trackId: String): TransferCheckpointEntity?


    @Query("SELECT * FROM transfer_checkpoints WHERE remoteDeviceId = :remoteDeviceId ORDER BY updatedAt DESC")
    suspend fun getCheckpointsForDevice(remoteDeviceId: String): List<TransferCheckpointEntity>


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: TransferCheckpointEntity)


    @Query("UPDATE transfer_checkpoints SET receivedBytes = :received, verifiedBytes = :verified, transferState = :state, updatedAt = :updatedAt WHERE transferKey = :key")
    suspend fun updateProgress(key: String, received: Long, verified: Long, state: String, updatedAt: Long): Int


    @Query("DELETE FROM transfer_checkpoints WHERE transferKey = :key")
    suspend fun deleteCheckpoint(key: String)


    @Query("DELETE FROM transfer_checkpoints WHERE remoteDeviceId = :remoteDeviceId AND transferState = 'COMPLETED'")
    suspend fun clearCompletedCheckpoints(remoteDeviceId: String)
}


@Dao
interface ApprovedSyncPlanDao {
    @Query("SELECT * FROM approved_sync_plans WHERE planId = :planId")
    suspend fun getPlan(planId: String): ApprovedSyncPlanEntity?


    @Query("SELECT * FROM approved_sync_plans WHERE endpointId = :endpointId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestPlanForEndpoint(endpointId: String): ApprovedSyncPlanEntity?


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlan(plan: ApprovedSyncPlanEntity)


    @Query("UPDATE approved_sync_plans SET executionState = :state, executedAt = COALESCE(:executedAt, executedAt), completedAt = COALESCE(:completedAt, completedAt), errorMessage = :errorMessage WHERE planId = :planId")
    suspend fun updateExecutionState(
        planId: String,
        state: String,
        executedAt: Long? = null,
        completedAt: Long? = null,
        errorMessage: String? = null,
    ): Int


    @Query("UPDATE approved_sync_plans SET executionState = :nextState, executedAt = COALESCE(:executedAt, executedAt), completedAt = COALESCE(:completedAt, completedAt), errorMessage = :errorMessage WHERE planId = :planId AND executionState = :expectedState")
    suspend fun transitionState(
        planId: String,
        expectedState: String,
        nextState: String,
        executedAt: Long? = null,
        completedAt: Long? = null,
        errorMessage: String? = null,
    ): Int


    @Query("DELETE FROM approved_sync_plans WHERE planId = :planId")
    suspend fun deletePlan(planId: String)
}