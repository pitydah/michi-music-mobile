package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** To be executed with the project's real Room/Robolectric Gradle suite. */
@RunWith(RobolectricTestRunner::class)
class ArtistArtworkAutomatedCommitTest {
    private lateinit var database: ArtistEnrichmentCacheDatabase
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val source = "device_music_root"
    private val qid = "Q48284"

    @Before fun before() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }

    @After fun after() { database.close() }

    private val asset get() = ArtistArtworkAssetRecord(
        assetId = "sha256:${"a".repeat(64)}", confirmedMbid = mbid, verifiedQid = qid,
        source = "COMMONS_P18", sourcePageUrl = "https://commons.wikimedia.org/wiki/File:test.jpg",
        imageUrl = "https://upload.wikimedia.org/test.jpg", localRelativePath = "artist-artwork/a.jpg",
        sha256 = "a".repeat(64), mimeType = "image/jpeg", width = 600, height = 600,
        licenseId = "CC BY-SA 4.0", licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        attribution = "Photographer", evidence = "VERIFIED_QID_REFERENCE", downloadedAtMs = 1,
    )
    private val link get() = ArtistArtworkLinkRecord(
        libraryId = "lib", creditKey = "artist", albumGroupKey = "assoc", slot = "ARTIST_THUMB",
        confirmedMbid = mbid, assetId = asset.assetId, verifiedQid = qid,
        source = "COMMONS_P18", sourcePageUrl = asset.sourcePageUrl,
        imageUrl = asset.imageUrl, licenseId = asset.licenseId, licenseUrl = asset.licenseUrl,
        attribution = asset.attribution, evidence = "VERIFIED_QID_REFERENCE", linkedAtMs = 1,
    )
    private val selection get() = ArtistArtworkSelectionRecord(
        libraryId = "lib", creditKey = "artist", albumGroupKey = "assoc", slot = "ARTIST_THUMB",
        assetId = asset.assetId, confirmedMbid = mbid, focusX = .5f, focusY = .5f,
        manuallyChosen = false, revision = 0, updatedAtMs = 1,
    )

    private suspend fun seed() {
        val dao = database.artistEnrichmentDao()
        dao.put(ArtistEnrichmentRecord(
            libraryId = "lib", creditKey = "artist", albumGroupKey = "identity", confirmedMbid = mbid,
            profileJson = """{"mbid":"$mbid","wikidataQid":"$qid"}""", savedAtMs = 1,
            staleAfterMs = 1000, source = "musicbrainz-manual-selection",
            biographySourceUrl = null, portraitSourceUrl = null,
            portraitLicense = null, portraitLicenseUrl = null, portraitAttribution = null,
        ))
        dao.putDurableAssociation(DurableArtistAssociationRecord(
            libraryId = "lib", creditKey = "artist", associationId = "assoc",
            identityAssociationId = "identity", createdAtMs = 1, updatedAtMs = 1,
        ))
        dao.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            libraryId = "lib", albumKey = "album-a", creditKey = "artist",
            associationId = "assoc", evidenceType = "EXPLICIT", confirmedAtMs = 1,
        ))
        // A bound album is not automatically source-proven. Seed a genuine
        // catalog receipt and explicit post-observation confirmation.
        val journal = database.artistArtworkCatalogJournalDao()
        journal.insertSourceCatalogCredits(listOf(ArtistArtworkSourceCatalogCreditRecord(
            "lib", source, "album-a", "artist", observedAtMs = 0,
        )))
        journal.captureBindingSourceProofs("lib", "artist", "assoc", source,
            baselineAtMs = 0, nowMs = 5)
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            libraryId = "lib", creditKey = "artist", associationId = "assoc",
            albumKey = "album-a", sourceId = source, updatedAtMs = 1,
        ))
    }

    @Test fun verifiedExactBindingCanCommitAutomatically() = runBlocking {
        seed()
        val revision = database.artistEnrichmentDao()
            .persistArtworkAutomated(asset, link, selection, source)
        assertNotNull(revision)
        assertEquals(1, database.artistEnrichmentDao().countArtworkSelectionsForLibrary("lib"))
    }

    @Test fun deferredPendingEventCannotAuthorizeLateArtworkWrite() = runBlocking {
        seed()
        val journal = database.artistArtworkCatalogJournalDao()
        val until = System.currentTimeMillis() + 600_000
        assertEquals(1, journal.deferPendingForAssociation("lib", "artist", "assoc", source, until))
        assertNull(database.artistEnrichmentDao().persistArtworkAutomated(
            asset, link, selection, source))
        assertEquals(0, database.artistEnrichmentDao().countArtworkSelectionsForLibrary("lib"))
    }

    @Test fun manualChoiceSavedDuringDownloadCannotBeReplaced() = runBlocking {
        seed()
        val dao = database.artistEnrichmentDao()
        dao.persistArtwork(asset, link, selection.copy(manuallyChosen = true))
        assertNull(dao.persistArtworkAutomated(asset.copy(assetId = "sha256:${"b".repeat(64)}"),
            link.copy(assetId = "sha256:${"b".repeat(64)}"),
            selection.copy(assetId = "sha256:${"b".repeat(64)}"), source))
        assertEquals(true, dao.getArtworkSelections("lib", "artist", "assoc", mbid).single().manuallyChosen)
    }

    @Test fun manualSlotFromPreviousMbidRemainsProtected() = runBlocking {
        seed()
        val previousMbid = "11111111-1111-4111-8111-111111111111"
        val oldAsset = asset.copy(assetId = "sha256:${"c".repeat(64)}", confirmedMbid = previousMbid)
        val oldLink = link.copy(assetId = oldAsset.assetId, confirmedMbid = previousMbid)
        val oldSelection = selection.copy(assetId = oldAsset.assetId,
            confirmedMbid = previousMbid, manuallyChosen = true)
        val dao = database.artistEnrichmentDao()
        // Legacy/manual rows may predate the current identity. Seed them directly:
        // today's guarded manual save correctly refuses a foreign MBID, but the
        // automated write must still preserve an existing manual slot.
        dao.putArtworkAsset(oldAsset)
        dao.putArtworkLink(oldLink)
        dao.putArtworkSelection(oldSelection)
        assertNull(dao.persistArtworkAutomated(asset, link, selection, source))
        assertEquals(previousMbid, dao.getArtworkSelections("lib", "artist", "assoc", previousMbid)
            .single().confirmedMbid)
    }

    @Test fun revokedBindingOrWrongSourceCannotPublish() = runBlocking {
        seed()
        val dao = database.artistEnrichmentDao()
        val journal = database.artistArtworkCatalogJournalDao()
        assertNull(dao.persistArtworkAutomated(asset, link, selection, "saf-other"))
        journal.finish("lib", "artist", "assoc", "album-a", source, "STALE_SOURCE", 2)
        assertNull(dao.persistArtworkAutomated(asset, link, selection, source))
        assertEquals(0, dao.countArtworkSelectionsForLibrary("lib"))
    }

    @Test fun confirmedBindingsHaveStableKeysetOrderWithoutAudioQueries() = runBlocking {
        seed()
        val dao = database.artistEnrichmentDao()
        dao.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            libraryId = "lib", albumKey = "album-b", creditKey = "artist",
            associationId = "assoc", evidenceType = "EXPLICIT", confirmedAtMs = 2,
        ))
        val journal = database.artistArtworkCatalogJournalDao()
        val first = journal.bindingsAfter("lib", "", "", 1).single()
        assertEquals("album-a", first.albumKey)
        val second = journal.bindingsAfter("lib", first.albumKey, first.creditKey, 1).single()
        assertEquals("album-b", second.albumKey)
        assertEquals(emptyList<ArtistAlbumCreditBindingRecord>(),
            journal.bindingsAfter("lib", second.albumKey, second.creditKey, 1))
    }

    @Test fun profileQidChangedDuringProviderRequestRejectsWrite() = runBlocking {
        seed()
        val dao = database.artistEnrichmentDao()
        dao.put(dao.get("lib", "artist", "identity")!!.copy(
            profileJson = """{"mbid":"$mbid","wikidataQid":"Q123456"}""",
        ))
        assertNull(dao.persistArtworkAutomated(asset, link, selection, source))
        assertEquals(0, dao.countArtworkSelectionsForLibrary("lib"))
    }
    @Test fun boundAlbumWithoutSourceProofRemainsPendingOnOtherAlbumCompletion() = runBlocking {
        seed()
        val dao = database.artistEnrichmentDao()
        val journal = database.artistArtworkCatalogJournalDao()
        dao.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            "lib", "album-unproven", "artist", "assoc", "EXPLICIT", 2,
        ))
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "assoc", "album-unproven", source, updatedAtMs = 2,
        ))
        assertEquals(1, journal.finishBoundAssociation(
            "lib", "artist", "assoc", source, "READY", 3))
        assertEquals(listOf("album-unproven"),
            journal.pending("lib", "artist", "assoc", Long.MAX_VALUE).map { it.albumKey })
    }

    @Test fun terminalStateAffectsOnlyActuallyBoundAlbumsAndCorrectRoot() = runBlocking {
        seed()
        val dao = database.artistEnrichmentDao()
        val journal = database.artistArtworkCatalogJournalDao()
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "assoc", "orphan", source, updatedAtMs = 2,
        ))
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "assoc", "other-root", "saf-changed", updatedAtMs = 2,
        ))
        dao.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            "lib", "album-b", "artist", "assoc", "EXPLICIT", 2,
        ))
        journal.insertSourceCatalogCredits(listOf(ArtistArtworkSourceCatalogCreditRecord(
            "lib", source, "album-b", "artist", observedAtMs = 0,
        )))
        journal.captureBindingSourceProofs("lib", "artist", "assoc", source,
            baselineAtMs = 0, nowMs = 5)
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "assoc", "album-b", source, updatedAtMs = 2,
        ))
        assertEquals(2, journal.finishBoundAssociation("lib", "artist", "assoc", source, "READY", 3))
        assertEquals(setOf("orphan", "other-root"),
            journal.pending("lib", "artist", "assoc", Long.MAX_VALUE).map { it.albumKey }.toSet())
    }
}
