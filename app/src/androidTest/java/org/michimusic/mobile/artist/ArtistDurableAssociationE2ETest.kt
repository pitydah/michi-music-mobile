package org.michimusic.mobile.artist

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkEvidence
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkRights
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.DownloadedArtwork
import org.michimusic.mobile.artist.artwork.RoomArtistArtworkStore
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor

/**
 * Real file-backed Room + private-file evidence for the durable association contract.
 * No provider is contacted. A saved identity and both artwork slots must survive an
 * album membership change and a database reopen without re-identifying the artist.
 */
@RunWith(AndroidJUnit4::class)
class ArtistDurableAssociationE2ETest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "durable-association-${System.nanoTime()}.db"
    private val libraryId = "lib-durable"
    private val credit = "Midnight Oil"
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val albumsA = listOf("album-diesel-and-dust")
    private val albumsB = listOf("album-diesel-and-dust", "album-blue-sky-mining")
    private lateinit var database: ArtistEnrichmentCacheDatabase
    private lateinit var store: RoomArtistEnrichmentSelectionStore
    private lateinit var artworkStore: ArtistArtworkStore

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        open()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(dbName)
        File(context.filesDir, "artist-artwork").listFiles()
            ?.filter { it.name.startsWith("durable-") }
            ?.forEach { it.delete() }
    }

    private fun open() {
        database = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, dbName)
            .addMigrations(
                ArtistEnrichmentCacheDatabase.MIGRATION_1_2,
                ArtistEnrichmentCacheDatabase.MIGRATION_2_3,
                ArtistEnrichmentCacheDatabase.MIGRATION_3_4,
                ArtistEnrichmentCacheDatabase.MIGRATION_4_5,
                ArtistEnrichmentCacheDatabase.MIGRATION_5_6,
                ArtistEnrichmentCacheDatabase.MIGRATION_6_7,
                ArtistEnrichmentCacheDatabase.MIGRATION_7_8,
            )
            .build()
        store = RoomArtistEnrichmentSelectionStore(database) { 100L }
        artworkStore = RoomArtistArtworkStore(database, context) { 200L }
    }

    private fun profile() = ArtistExternalProfile(
        mbid = mbid,
        name = credit,
        biography = "Banda australiana de rock.",
        biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
        wikidataQid = "Q48284",
        biographyOutcome = org.michimusic.mobile.artist.ArtistMetadataOutcome.SUCCESS,
    )

    private fun writePng(): DownloadedArtwork {
        val directory = File(context.filesDir, "artist-artwork").apply { mkdirs() }
        val file = File(directory, "durable-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return DownloadedArtwork(
            relativePath = file.relativeTo(context.filesDir).invariantSeparatorsPath,
            sha256 = hash,
            width = 960,
            height = 640,
            mimeType = "image/png",
        )
    }

    private fun candidate() = ArtistArtworkCandidate(
        source = ArtworkSource.COMMONS_P18,
        stableAssetId = "commons:durable",
        confirmedMbid = mbid,
        verifiedQid = "Q48284",
        canonicalSourcePage = "https://commons.wikimedia.org/wiki/File:Midnight_Oil.png",
        imageUrl = "https://thumb.wikimedia.org/Midnight_Oil.png",
        originalWidth = 960,
        originalHeight = 640,
        mimeType = "image/png",
        licenseId = "CC BY-SA 2.0",
        licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
        author = "Autor",
        attribution = "Autor",
        evidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
        rights = ArtworkRights.VERIFIED_ALLOWED,
    )

    private suspend fun saveBothSlots(anchor: ArtistIdentityAnchor) {
        val thumb = artworkStore.save(
            anchor, candidate(), ArtworkSlot.ARTIST_THUMB, writePng(),
            ArtworkFocus(0.5f, 0.5f), manuallyChosen = false,
        )
        val hero = artworkStore.save(
            anchor, candidate().copy(stableAssetId = "commons:durable-hero"),
            ArtworkSlot.ARTIST_HERO, writePng(),
            ArtworkFocus(0.5f, 0.45f), manuallyChosen = true,
        )
        assertEquals(ArtworkLoadStatus.READY, thumb.status)
        assertEquals(ArtworkLoadStatus.READY, hero.status)
        assertNotNull(thumb.localImagePath)
        assertNotNull(hero.localImagePath)
    }

    @Test
    fun confirmedAssociationAndBothSlotsSurviveAlbumChangeAndReopen() = runBlocking {
        store.save(libraryId, credit, albumsA, profile())
        val first = requireNotNull(store.load(libraryId, credit, albumsA))
        val canonical = requireNotNull(first.associationGroupKey)
        val anchor = requireNotNull(
            artistIdentityAnchor(libraryId, credit, albumsA, profile(), canonical),
        )
        saveBothSlots(anchor)

        database.close()
        open()

        val recovered = requireNotNull(store.load(libraryId, credit, albumsB))
        assertEquals(mbid, recovered.profile.mbid)
        assertEquals(canonical, recovered.associationGroupKey)
        assertTrue(recovered.needsConfirmation)
        val recoveredAnchor = requireNotNull(
            artistIdentityAnchor(
                libraryId, credit, albumsB, recovered.profile, recovered.associationGroupKey,
            ),
        )
        val slots = artworkStore.load(recoveredAnchor).associateBy { it.slot }
        assertEquals(ArtworkLoadStatus.READY, slots.getValue(ArtworkSlot.ARTIST_THUMB).status)
        assertEquals(ArtworkLoadStatus.READY, slots.getValue(ArtworkSlot.ARTIST_HERO).status)
        assertEquals(0.45f, slots.getValue(ArtworkSlot.ARTIST_HERO).focus.y, 0.0001f)
        assertTrue(slots.getValue(ArtworkSlot.ARTIST_HERO).manuallyChosen)

        store.save(libraryId, credit, albumsB, profile(), canonical)
        database.close()
        open()

        val exact = requireNotNull(store.load(libraryId, credit, albumsB))
        assertEquals(canonical, exact.associationGroupKey)
        assertEquals(mbid, exact.profile.mbid)
        assertFalse(exact.needsConfirmation)
        val exactAnchor = requireNotNull(
            artistIdentityAnchor(
                libraryId, credit, albumsB, exact.profile, exact.associationGroupKey,
            ),
        )
        val exactSlots = artworkStore.load(exactAnchor).associateBy { it.slot }
        assertEquals(ArtworkLoadStatus.READY, exactSlots.getValue(ArtworkSlot.ARTIST_THUMB).status)
        assertEquals(ArtworkLoadStatus.READY, exactSlots.getValue(ArtworkSlot.ARTIST_HERO).status)
    }

    @Test
    fun removingAnAlbumAlsoKeepsTheCanonicalAssociationAndBothSlots() = runBlocking {
        store.save(libraryId, credit, albumsB, profile())
        val canonical = requireNotNull(store.load(libraryId, credit, albumsB)?.associationGroupKey)
        val anchor = requireNotNull(
            artistIdentityAnchor(libraryId, credit, albumsB, profile(), canonical),
        )
        saveBothSlots(anchor)

        database.close()
        open()

        val removed = requireNotNull(store.load(libraryId, credit, albumsA))
        assertEquals(mbid, removed.profile.mbid)
        assertEquals(canonical, removed.associationGroupKey)
        assertTrue(removed.needsConfirmation)
        val removedAnchor = requireNotNull(
            artistIdentityAnchor(
                libraryId, credit, albumsA, removed.profile, removed.associationGroupKey,
            ),
        )
        val removedSlots = artworkStore.load(removedAnchor).associateBy { it.slot }
        assertEquals(ArtworkLoadStatus.READY, removedSlots.getValue(ArtworkSlot.ARTIST_THUMB).status)
        assertEquals(ArtworkLoadStatus.READY, removedSlots.getValue(ArtworkSlot.ARTIST_HERO).status)
        assertTrue(removedSlots.getValue(ArtworkSlot.ARTIST_HERO).manuallyChosen)

        store.save(libraryId, credit, albumsA, profile(), canonical)
        database.close()
        open()

        val exact = requireNotNull(store.load(libraryId, credit, albumsA))
        assertEquals(canonical, exact.associationGroupKey)
        assertFalse(exact.needsConfirmation)
    }

    @Test
    fun clearingAfterAlbumChangeDeletesOnlyTheCanonicalAssociation() = runBlocking {
        store.save(libraryId, credit, albumsA, profile())
        val canonical = requireNotNull(store.load(libraryId, credit, albumsA)?.associationGroupKey)
        val anchor = requireNotNull(
            artistIdentityAnchor(libraryId, credit, albumsA, profile(), canonical),
        )
        saveBothSlots(anchor)

        val recovered = requireNotNull(store.load(libraryId, credit, albumsB))
        store.clear(libraryId, credit, albumsB, recovered.associationGroupKey)
        artworkStore.clear(anchor)

        assertNull(store.load(libraryId, credit, albumsB))
        assertTrue(artworkStore.load(anchor).isEmpty())
    }
}
