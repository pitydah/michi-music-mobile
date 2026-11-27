package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.artist.ArtistConfirmedAlbumIdentityRow
import org.michimusic.data.artist.ArtistTrackEvidenceRecord
import org.michimusic.mobile.artist.ArtistHomonymMosaicPolicy
import org.michimusic.mobile.artist.ArtistMembershipOccurrence
import org.michimusic.mobile.artist.ArtistPresentationEntry
import org.michimusic.mobile.artist.ArtistExactMembershipProjection
import org.michimusic.mobile.artist.ArtistExactMembershipResult

internal class ArtistExplicitTrackPolicyTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private fun claim(id:String, sig:String=("a".repeat(64) + "\u0000" + "42")) = ArtistExactOccurrenceClaim(
        "lib","album","Alex","Alex","source\\u0000$id\\u0000/content/$id","track-$id",sig)
    private fun request(c:ArtistExactOccurrenceClaim,selected:String=a,confirmed:Boolean=true) =
        ArtistTrackChoiceRequest("lib","album",requireNotNull(c.trackId),"Alex",c.trackIdentity,
            selected,listOf(a,b),"local",3L,5L,confirmed)
    private val proof = ArtistTrackByteProof("a".repeat(64), 42)
    private fun row(c:ArtistExactOccurrenceClaim,selected:String=a) =
        requireNotNull(ArtistExplicitTrackChoicePolicy.prepare(request(c,selected),c,"local",3L,5L,1000L,proof))
    private fun ready(claims:List<ArtistExactOccurrenceClaim>,explicit:List<org.michimusic.data.artist.ArtistExplicitTrackIdentityRecord>) =
        ArtistExactOccurrenceIdentityPolicy.reconcile("lib",claims, emptyList(),emptyList(),explicit,"local",3L,5L)
            as ArtistExactOccurrenceIdentityResult.Ready
    @Test fun explicitClickIsMandatory() { val c=claim("1");assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c,confirmed=false),c,"local",3,5,100,proof)) }
    @Test fun neverAcceptOnlyOneCandidate() { val c=claim("1");assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c).copy(offeredMbids=listOf(a)),c,"local",3,5,100,proof)) }
    @Test fun rejectUserSuppliedUnknownIdentity() { val c=claim("1");assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c).copy(chosenMbid="33333333-3333-4333-8333-333333333333"),c,"local",3,5,100,proof)) }
    @Test fun rejectStaleRoot() { val c=claim("1");assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c),c,"local",4,5,100,proof)) }
    @Test fun rejectStaleCatalogueAtAdmission() { val c=claim("1");assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c),c,"local",3,6,100,proof)) }
    @Test fun rejectWrongTrackId() { val c=claim("1");assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c).copy(trackId="track-2"),c,"local",3,5,100,proof)) }
    @Test fun rejectEmptyContentSignature() { val c=claim("1","");assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c),c,"local",3,5,100,proof)) }
    @Test fun refusingToSaveWithoutBytesIsMandatory() {
        val c=claim("1")
        assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c),c,"local",3,5,100))
    }
    @Test fun migratedLegacyRecordIsNotAnActiveIdentity() {
        val c=claim("1")
        assertFalse(ArtistExplicitTrackChoicePolicy.matches(
            row(c).copy(sourceContentSha256="", sourceContentBytes=0),c,"local",3,5))
    }
    @Test fun unverifiedCatalogHashDoesNotActivateAStoredReceipt() {
        val c=claim("1")
        assertFalse(ArtistExplicitTrackChoicePolicy.matches(row(c),
            c.copy(localContentSignature="unknown\u000042"),"local",3,5))
    }
    @Test fun changedCatalogHashOrSizeDoesNotActivate() {
        val c=claim("1")
        assertFalse(ArtistExplicitTrackChoicePolicy.matches(row(c),
            c.copy(localContentSignature="b".repeat(64)+"\u000042"),"local",3,5))
        assertFalse(ArtistExplicitTrackChoicePolicy.matches(row(c),
            c.copy(localContentSignature="a".repeat(64)+"\u000043"),"local",3,5))
    }
    @Test fun sourceByteProofIsStoredWithoutPath() {
        val c=claim("1")
        val stored=row(c)
        assertEquals("a".repeat(64),stored.sourceContentSha256)
        assertEquals(42L,stored.sourceContentBytes)
        assertFalse(stored.toString().contains("/content/"))
    }
    @Test fun catalogSizeMustMatchEvenWithoutStrongHash() {
        val c=claim("1", "not-a-hash\u000043")
        assertEquals(null,ArtistExplicitTrackChoicePolicy.prepare(request(c),c,"local",3,5,100,proof))
    }
    @Test fun durableReceiptHasOnlyHashOfLocalTrackIdentity() { val c=claim("1");val result=row(c);assertEquals(64,result.trackKeySha256.length);assertFalse(result.toString().contains("/content/")) }
    @Test fun aColdStartCanReuseAnExactUnchangedReceipt() { val c=claim("1");assertTrue(ArtistExplicitTrackChoicePolicy.matches(row(c),c,"local",3,0)) }
    @Test fun changedContentInvalidatesReceipt() { val c=claim("1");assertFalse(ArtistExplicitTrackChoicePolicy.matches(row(c),c.copy(localContentSignature="changed"),"local",3,5)) }
    @Test fun changedUriInvalidatesReceipt() { val c=claim("1");assertFalse(ArtistExplicitTrackChoicePolicy.matches(row(c),c.copy(trackIdentity="new-uri"),"local",3,5)) }
    @Test fun changedSourceOrEpochInvalidatesReceipt() { val c=claim("1");assertFalse(ArtistExplicitTrackChoicePolicy.matches(row(c),c,"other",3,5));assertFalse(ArtistExplicitTrackChoicePolicy.matches(row(c),c,"local",4,5)) }
    @Test fun tagOnlyCannotConfirm() { val c=claim("1");val tag=ArtistTrackEvidenceRecord("lib","track-1","ARTIST_MBID",0,a,1);
        val r=ArtistExactOccurrenceIdentityPolicy.reconcile("lib",listOf(c),emptyList(),listOf(tag)) as ArtistExactOccurrenceIdentityResult.Ready
        assertEquals(null,r.identities.values.single().confirmedMbid)
    }
    @Test fun exactExplicitReceiptConfirmsOnlyOneTrack() { val c1=claim("1");val c2=claim("2");val r=ready(listOf(c1,c2),listOf(row(c1,a)));
        assertEquals(a,r.identities[ArtistExactOccurrenceKey("album",c1.trackIdentity)]?.confirmedMbid)
        assertEquals(null,r.identities[ArtistExactOccurrenceKey("album",c2.trackIdentity)]?.confirmedMbid)
    }
    @Test fun overlappingHomonymsNeedEveryTrackExplicit() {
        val c1=claim("1");val c2=claim("2")
        val onlyOne=ready(listOf(c1,c2),listOf(row(c1,a)))
        val incomplete=listOf(ArtistMembershipOccurrence("lib","album","Alex",c1.trackIdentity,a,true),
            ArtistMembershipOccurrence("lib","album","Alex",c2.trackIdentity,b,false))
        val entry=ArtistPresentationEntry("Alex",listOf("Alex"),listOf("album"),2,"lib",null)
        assertEquals(null,ArtistHomonymMosaicPolicy.split(entry,incomplete))
        val both=ready(listOf(c1,c2),listOf(row(c1,a),row(c2,b)))
        val all=listOf(ArtistMembershipOccurrence("lib","album","Alex",c1.trackIdentity,
            both.identities[ArtistExactOccurrenceKey("album",c1.trackIdentity)]?.confirmedMbid,true),
            ArtistMembershipOccurrence("lib","album","Alex",c2.trackIdentity,
                both.identities[ArtistExactOccurrenceKey("album",c2.trackIdentity)]?.confirmedMbid,true))
        val split=ArtistHomonymMosaicPolicy.split(entry,all)
        assertEquals(2,split?.size)
        val projection=ArtistExactMembershipProjection.prepare(requireNotNull(split).first(),all,"lib")
        assertTrue(projection is ArtistExactMembershipResult.Ready)
        assertFalse(onlyOne.identities.values.all { it.confirmedByExactTrackChoice })
    }
    @Test fun foreignLibraryRowFailsClosed() { val c=claim("1");val r=ArtistExactOccurrenceIdentityPolicy.reconcile("lib",listOf(c),emptyList(),emptyList(),listOf(row(c).copy(libraryId="foreign")),"local",3,5)
        assertTrue(r is ArtistExactOccurrenceIdentityResult.Blocked)
    }
    @Test fun duplicatedChoiceRowsRefused() { val c=claim("1");val r=ArtistExactOccurrenceIdentityPolicy.reconcile("lib",listOf(c),emptyList(),emptyList(),listOf(row(c),row(c)),"local",3,5)
        assertTrue(r is ArtistExactOccurrenceIdentityResult.Blocked)
    }
}
