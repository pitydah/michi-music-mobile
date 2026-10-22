package org.michimusic.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
