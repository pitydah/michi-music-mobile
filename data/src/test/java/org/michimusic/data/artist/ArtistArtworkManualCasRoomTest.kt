package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D15: real Room assertions to execute in OpenCode (not executed in Drive). */
@RunWith(RobolectricTestRunner::class)
class ArtistArtworkManualCasRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val otherMbid = "11111111-1111-4111-8111-111111111111"
    private val dao get() = db.artistEnrichmentDao()

    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }
    @After fun cleanup() = db.close()

    private suspend fun seed() {
        dao.put(ArtistEnrichmentRecord(
            libraryId="lib",creditKey="credit",albumGroupKey="identity",confirmedMbid=mbid,
            profileJson="""{"mbid":"$mbid"}""",savedAtMs=1,staleAfterMs=1000,
            source="musicbrainz-manual-selection",biographySourceUrl=null,portraitSourceUrl=null,
            portraitLicense=null,portraitLicenseUrl=null,portraitAttribution=null,
        ))
        dao.putDurableAssociation(DurableArtistAssociationRecord(
            libraryId="lib",creditKey="credit",associationId="group",identityAssociationId="identity",
            createdAtMs=1,updatedAtMs=1,
        ))
        dao.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            libraryId="lib",albumKey="album-a",creditKey="credit",associationId="group",
            evidenceType="EXPLICIT",confirmedAtMs=1,
        ))
    }
    private fun asset(letter: String, identity: String = mbid): ArtistArtworkAssetRecord {
        val digest = letter.repeat(64)
        return ArtistArtworkAssetRecord(
            assetId="sha256:$digest",confirmedMbid=identity,verifiedQid=null,
            source="OPENVERSE",sourcePageUrl="https://www.flickr.com/photos/example/$letter",
            imageUrl="https://live.staticflickr.com/example/$letter.jpg",
            localRelativePath="artist-artwork/$digest.jpg",sha256=digest,
            mimeType="image/jpeg",width=800,height=800,licenseId="CC BY 4.0",
            licenseUrl="https://creativecommons.org/licenses/by/4.0/",
            attribution="Test",evidence="USER_SELECTED",downloadedAtMs=1,
        )
    }
    private fun link(a: ArtistArtworkAssetRecord,slot:String="ARTIST_THUMB",identity:String=mbid) =
        ArtistArtworkLinkRecord(
            libraryId="lib",creditKey="credit",albumGroupKey="group",slot=slot,
            confirmedMbid=identity,assetId=a.assetId,verifiedQid=null,
            source="OPENVERSE",sourcePageUrl=a.sourcePageUrl,imageUrl=a.imageUrl,
            licenseId=a.licenseId,licenseUrl=a.licenseUrl,attribution=a.attribution,
            evidence="USER_SELECTED",linkedAtMs=1,
        )
    private fun choice(a: ArtistArtworkAssetRecord,slot:String="ARTIST_THUMB",identity:String=mbid) =
        ArtistArtworkSelectionRecord(
            libraryId="lib",creditKey="credit",albumGroupKey="group",slot=slot,
            assetId=a.assetId,confirmedMbid=identity,focusX=.5f,focusY=.5f,
            manuallyChosen=true,revision=0,updatedAtMs=1,
        )

    @Test fun firstManualWriteNeedsConfirmedAssociationAndExactBinding() = runBlocking {
        val a=asset("a")
        assertNull(dao.persistArtworkManuallyAuthorized(a,link(a),choice(a),null,null,false))
        seed()
        assertNotNull(dao.persistArtworkManuallyAuthorized(a,link(a),choice(a),null,null,false))
        assertEquals(a.assetId,dao.getArtworkSelections("lib","credit","group",mbid).single().assetId)
    }

    @Test fun manualReplacementRequiresConsentAndCompareAndSwapVersion() = runBlocking {
        seed()
        val a=asset("a");val b=asset("b")
        val first=requireNotNull(dao.persistArtworkManuallyAuthorized(a,link(a),choice(a),null,null,false))
        assertNull(dao.persistArtworkManuallyAuthorized(b,link(b),choice(b),a.assetId,first,false))
        assertNull(dao.persistArtworkManuallyAuthorized(b,link(b),choice(b),"wrong",first,true))
        assertNull(dao.persistArtworkManuallyAuthorized(b,link(b),choice(b),a.assetId,first+1,true))
        val second=dao.persistArtworkManuallyAuthorized(b,link(b),choice(b),a.assetId,first,true)
        assertNotNull(second)
        assertNull(dao.persistArtworkManuallyAuthorized(a,link(a),choice(a),a.assetId,first,true))
        assertEquals(b.assetId,dao.getArtworkSelections("lib","credit","group",mbid).single().assetId)
        assertTrue(requireNotNull(second)>first)
    }

    @Test fun emptyReceiptCannotOverwriteSlotCreatedDuringDownload() = runBlocking {
        seed()
        val a=asset("a");val b=asset("b")
        assertNotNull(dao.persistArtworkManuallyAuthorized(a,link(a),choice(a),null,null,false))
        assertNull(dao.persistArtworkManuallyAuthorized(b,link(b),choice(b),null,null,true))
    }

    @Test fun incompatibleMbidCannotBeOverwrittenEvenWithConsent() = runBlocking {
        seed()
        val a=asset("a",otherMbid);val b=asset("b")
        dao.putArtworkAsset(a)
        dao.putArtworkLink(link(a,identity=otherMbid))
        dao.putArtworkSelection(choice(a,identity=otherMbid).copy(revision=9))
        assertNull(dao.persistArtworkManuallyAuthorized(b,link(b),choice(b),a.assetId,9,true))
        assertEquals(otherMbid,dao.artworkSelectionForExactSlot("lib","credit","group","ARTIST_THUMB")?.confirmedMbid)
    }

    @Test fun mosaicAndHeroAreVersionedIndependently() = runBlocking {
        seed()
        val a=asset("a");val b=asset("b")
        assertNotNull(dao.persistArtworkManuallyAuthorized(a,link(a),choice(a),null,null,false))
        assertNotNull(dao.persistArtworkManuallyAuthorized(
            b,link(b,slot="ARTIST_HERO"),choice(b,slot="ARTIST_HERO"),null,null,false))
        val selections=dao.getArtworkSelections("lib","credit","group",mbid)
        assertEquals(2,selections.size)
        assertEquals(setOf(a.assetId,b.assetId),selections.map{it.assetId}.toSet())
    }

    private fun cleanupRow(group: String, id: String, time: Long) = ArtistArtworkCleanupRecord(
        cleanupId = group, libraryId = "lib", creditKey = "credit", associationId = "group",
        assetId = id, localRelativePath = "artist-artwork/$id.jpg", sha256 = "a".repeat(64),
        createdAtMs = time, state = "PENDING",
    )

    @Test fun cleanupCursorReachesLaterPendingRowsEvenIfFirstBatchRemainsPending() = runBlocking {
        repeat(130) { i -> dao.insertArtworkCleanup(cleanupRow("group", i.toString().padStart(3, '0'), 1)) }
        val first = dao.pendingArtworkCleanup(64)
        val last = first.last()
        val second = dao.pendingArtworkCleanupAfter(last.createdAtMs, last.cleanupId, last.assetId, 64)
        assertEquals(64, first.size)
        assertEquals(64, second.size)
        assertEquals("064", second.first().assetId)
        val tail = second.last()
        assertEquals(listOf("128", "129"), dao.pendingArtworkCleanupAfter(
            tail.createdAtMs, tail.cleanupId, tail.assetId, 64).map { it.assetId })
        assertEquals(64, dao.pendingArtworkCleanup(64).size)
    }

    @Test fun cleanupCursorExcludesTerminalRowsAndOrdersCompositeKey() = runBlocking {
        val rows = listOf(cleanupRow("b", "a", 1), cleanupRow("a", "b", 1),
            cleanupRow("a", "a", 1), cleanupRow("a", "a", 2).copy(cleanupId = "c"))
        rows.forEach { dao.insertArtworkCleanup(it) }
        assertEquals(1, dao.finishArtworkCleanup("a", "b", "REVIEW_REQUIRED"))
        val after = dao.pendingArtworkCleanupAfter(1, "a", "a", 64)
        assertEquals(listOf("b", "c"), after.map { it.cleanupId })
        assertTrue(dao.pendingArtworkCleanupAfter(2, "c", "a", 64).isEmpty())
    }
}
