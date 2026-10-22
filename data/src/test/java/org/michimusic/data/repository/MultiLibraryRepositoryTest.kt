package org.michimusic.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.LibraryType
import org.michimusic.core.models.LocationAvailability
import org.michimusic.core.models.TrackIdentity
import org.michimusic.core.models.TrackLocation
import org.michimusic.core.models.TrackLocationType
import org.michimusic.data.cache.LibraryEntity
import org.michimusic.data.cache.MichiDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MultiLibraryRepositoryTest {

    private lateinit var db: MichiDatabase
    private lateinit var repository: MusicLibraryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        kotlinx.coroutines.runBlocking {
            db.libraryDao().insertLibrary(
                org.michimusic.data.cache.LibraryEntity(
                    libraryId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID,
                    name = "Este teléfono",
                    type = "DEVICE_LOCAL",
                    isWritable = false,
                )
            )
        }

        repository = DefaultMusicLibraryRepository(
            libraryDao = db.libraryDao(),
            identityDao = db.trackIdentityDao(),
            locationDao = db.trackLocationDao(),
            membershipDao = db.libraryMembershipDao(),
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `createUserLibrary creates custom writable library`() = runTest {
        val lib = repository.createUserLibrary("Mi Colección Hi-Res")
        assertEquals("Mi Colección Hi-Res", lib.name)
        assertEquals(LibraryType.USER_CUSTOM, lib.type)
        assertTrue(lib.isWritable)

        val libs = repository.observeLibraries().first()
        assertTrue(libs.any { it.id == lib.id })
    }

    @Test
    fun `registerTrackWithLocation and resolvePreferredLocation prioritizes SAF over MediaStore and Synced`() = runTest {
        val trackId = "track_audiophile_1"
        val identity = TrackIdentity(
            id = trackId,
            title = "Acoustic Sunset",
            artist = "Michi Ensemble",
            album = "Audiophile Sessions",
            durationMs = 240000L,
            contentHash = "hash_sha256_abcdef",
        )

        // Location 1: MediaStore content://
        val locMediaStore = TrackLocation(
            id = "loc_ms_1",
            trackId = trackId,
            type = TrackLocationType.MEDIASTORE,
            uri = "content://media/external/audio/media/1001",
            availability = LocationAvailability.AVAILABLE,
        )

        // Location 2: SAF content://
        val locSaf = TrackLocation(
            id = "loc_saf_1",
            trackId = trackId,
            type = TrackLocationType.SAF,
            uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FSunset.flac",
            availability = LocationAvailability.AVAILABLE,
        )

        // Location 3: Synced managed file://
        val locSynced = TrackLocation(
            id = "loc_sync_1",
            trackId = trackId,
            type = TrackLocationType.SYNCED_MANAGED,
            uri = "file:///data/user/0/org.michimusic.mobile/files/synced/1001.flac",
            availability = LocationAvailability.AVAILABLE,
        )

        // Register track identity and locations
        repository.registerTrackWithLocation(identity, locMediaStore)
        db.trackLocationDao().insertLocation(
            org.michimusic.data.cache.TrackLocationEntity(
                locationId = locSaf.id,
                trackId = trackId,
                kind = locSaf.type.name,
                uri = locSaf.uri,
                availability = locSaf.availability.name,
            )
        )
        db.trackLocationDao().insertLocation(
            org.michimusic.data.cache.TrackLocationEntity(
                locationId = locSynced.id,
                trackId = trackId,
                kind = locSynced.type.name,
                uri = locSynced.uri,
                availability = locSynced.availability.name,
            )
        )

        // SAF is highest priority
        val preferred = repository.resolvePreferredLocation(trackId)
        assertNotNull(preferred)
        assertEquals(TrackLocationType.SAF, preferred?.type)
        assertEquals(locSaf.uri, preferred?.uri)
    }

    @Test
    fun `same track can belong to multiple libraries without duplication of identity`() = runTest {
        val userLib = repository.createUserLibrary("Favoritos Jazz")
        val trackId = "track_jazz_42"
        val identity = TrackIdentity(
            id = trackId,
            title = "Blue Train",
            artist = "John Coltrane",
            album = "Blue Train",
            durationMs = 643000L,
        )
        val loc = TrackLocation(
            id = "loc_jazz_42",
            trackId = trackId,
            type = TrackLocationType.MEDIASTORE,
            uri = "content://media/audio/42",
        )

        repository.registerTrackWithLocation(identity, loc, libraryId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID)
        repository.addTrackToLibrary(libraryId = userLib.id, trackId = trackId)

        val memberships = db.libraryMembershipDao().getLibrariesForTrack(trackId)
        assertEquals(2, memberships.size)
        assertTrue(memberships.contains(MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID))
        assertTrue(memberships.contains(userLib.id))

        // Observe tracks in userLib scope
        val tracksInCustomLib = repository.observeTracks(LibraryScope.One(userLib.id)).first()
        assertEquals(1, tracksInCustomLib.size)
        assertEquals("Blue Train", tracksInCustomLib.first().identity.title)
    }

    @Test
    fun `resolvePreferredLocation skips non-existent local file and falls back to media store`() = runTest {
        val trackId = "track_fallback_test"
        val identity = TrackIdentity(
            id = trackId,
            title = "Missing File Song",
            artist = "Michi Band",
            album = "Test Album",
            durationMs = 180000L,
        )

        val nonExistentLocalLoc = TrackLocation(
            id = "loc_missing_file",
            trackId = trackId,
            type = TrackLocationType.SAF,
            uri = "/storage/emulated/0/Music/non_existent_file.flac",
            availability = LocationAvailability.AVAILABLE,
        )

        val validMediaLoc = TrackLocation(
            id = "loc_media_fallback",
            trackId = trackId,
            type = TrackLocationType.MEDIASTORE,
            uri = "content://media/external/audio/media/9999",
            availability = LocationAvailability.AVAILABLE,
        )

        repository.registerTrackWithLocation(identity, nonExistentLocalLoc)
        db.trackLocationDao().insertLocation(
            org.michimusic.data.cache.TrackLocationEntity(
                locationId = validMediaLoc.id,
                trackId = trackId,
                kind = validMediaLoc.type.name,
                uri = validMediaLoc.uri,
                availability = validMediaLoc.availability.name,
            )
        )

        // Non-existent file path /storage/... will fail java.io.File.exists(), falling back to content:// MEDIASTORE
        val preferred = repository.resolvePreferredLocation(trackId)
        assertNotNull(preferred)
        assertEquals(TrackLocationType.MEDIASTORE, preferred?.type)
        assertEquals(validMediaLoc.uri, preferred?.uri)
    }

    @Test
    fun `registerTrackWithLocation atomically commits identity, location, and membership`() = runTest {
        val trackId = "track_atomic_1"
        val identity = TrackIdentity(
            id = trackId,
            title = "Atomic Song",
            artist = "Atomic Artist",
            album = "Atomic Album",
            durationMs = 210000L,
        )
        val location = TrackLocation(
            id = "loc_atomic_1",
            trackId = trackId,
            type = TrackLocationType.SAF,
            uri = "/storage/emulated/0/Music/atomic.mp3",
            availability = LocationAvailability.AVAILABLE,
        )

        val result = repository.registerTrackWithLocation(identity, location, libraryId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID)
        assertTrue(result.isSuccess)

        val retrievedIdentity = db.trackIdentityDao().getTrackIdentity(trackId)
        assertNotNull(retrievedIdentity)
        assertEquals("Atomic Song", retrievedIdentity?.title)

        val retrievedLocations = db.trackLocationDao().getLocationsForTrack(trackId)
        assertEquals(1, retrievedLocations.size)
        assertEquals("loc_atomic_1", retrievedLocations.first().locationId)

        val retrievedLibs = db.libraryMembershipDao().getLibrariesForTrack(trackId)
        assertEquals(listOf(MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID), retrievedLibs)
    }

    @Test
    fun `registerTrackWithLocation atomic rollback leaves no orphan records on constraint failure`() = runTest {
        val trackId = "track_rollback_fail"
        val identity = TrackIdentity(
            id = trackId,
            title = "Rollback Song",
            artist = "Rollback Artist",
            album = "Rollback Album",
            durationMs = 180000L,
        )
        val location = TrackLocation(
            id = "loc_rollback_fail",
            trackId = trackId,
            type = TrackLocationType.SAF,
            uri = "/storage/emulated/0/Music/rollback.mp3",
            availability = LocationAvailability.AVAILABLE,
        )

        // Attempt to register with a non-existent foreign library ID (violating foreign key constraint on library_tracks)
        val invalidLibraryId = "non_existent_library_999"
        val result = repository.registerTrackWithLocation(identity, location, libraryId = invalidLibraryId)

        // Must fail with constraint exception
        assertTrue(result.isFailure)

        // Verify full transactional rollback: no identity or location record persisted
        val retrievedIdentity = db.trackIdentityDao().getTrackIdentity(trackId)
        assertNull("TrackIdentity must be rolled back on atomic failure", retrievedIdentity)

        val retrievedLocations = db.trackLocationDao().getLocationsForTrack(trackId)
        assertTrue("TrackLocation must be rolled back on atomic failure", retrievedLocations.isEmpty())

        val retrievedLibs = db.libraryMembershipDao().getLibrariesForTrack(trackId)
        assertTrue("Library membership must be empty", retrievedLibs.isEmpty())
    }

    @Test
    fun `getTrackIdentity retrieves registered track identity correctly`() = runTest {
        val trackId = "track_lookup_test"
        val identity = TrackIdentity(
            id = trackId,
            title = "Lookup Title",
            artist = "Lookup Artist",
            album = "Lookup Album",
            durationMs = 123456L,
        )
        val location = TrackLocation(
            id = "loc_lookup_test",
            trackId = trackId,
            type = TrackLocationType.SAF,
            uri = "/storage/emulated/0/Music/lookup.mp3",
        )
        repository.registerTrackWithLocation(identity, location)

        val retrieved = repository.getTrackIdentity(trackId)
        assertNotNull(retrieved)
        assertEquals("Lookup Title", retrieved?.title)
        assertEquals("Lookup Artist", retrieved?.artist)
    }
}
