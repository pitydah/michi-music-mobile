package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.data.artist.ArtistConfirmedAlbumIdentityRow
import org.michimusic.data.artist.ArtistTrackEvidenceRecord
import org.michimusic.mobile.artist.ArtistMembershipOccurrence

internal class ArtistExactOccurrenceIdentityPolicyTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private fun claim(album: String, key: String, trackId: String? = key,
                      credit: String = "Alex", albumCredit: String = "Alex", lib: String = "lib") =
        ArtistExactOccurrenceClaim(lib, album, albumCredit, credit, key, trackId)
    private fun binding(album: String, mbid: String = a, creditKey: String = "alex", lib: String = "lib",
                        evidence: String = "USER_EXPLICIT_BINDING") =
        ArtistConfirmedAlbumIdentityRow(lib, album, creditKey, "association:$album:$mbid",
            evidence, 100L, mbid)
    private fun tag(track: String, mbid: String = b, field: String = "ARTIST_MBID", lib: String = "lib") =
        ArtistTrackEvidenceRecord(lib, track, field, 0, mbid, 100L)
    private fun ready(rows: List<ArtistExactOccurrenceClaim>,
                      binds: List<ArtistConfirmedAlbumIdentityRow> = emptyList(),
                      tags: List<ArtistTrackEvidenceRecord> = emptyList()) =
        ArtistExactOccurrenceIdentityPolicy.reconcile("lib", rows, binds, tags)
            as ArtistExactOccurrenceIdentityResult.Ready
    private fun blocked(rows: List<ArtistExactOccurrenceClaim>,
                        binds: List<ArtistConfirmedAlbumIdentityRow> = emptyList(),
                        tags: List<ArtistTrackEvidenceRecord> = emptyList()) =
        ArtistExactOccurrenceIdentityPolicy.reconcile("lib", rows, binds, tags)
            as ArtistExactOccurrenceIdentityResult.Blocked

    @Test fun twoIdenticalNamesGetDifferentMbidsByAlbum() {
        val res = ready(listOf(claim("album-a", "track-1"), claim("album-b", "track-2")),
            listOf(binding("album-a", a), binding("album-b", b)))
        assertEquals(a, res.identities[ArtistExactOccurrenceKey("album-a", "track-1")]?.confirmedMbid)
        assertEquals(b, res.identities[ArtistExactOccurrenceKey("album-b", "track-2")]?.confirmedMbid)
    }
    @Test fun guestTagNeverBecomesConfirmedIdentity() {
        val res = ready(listOf(claim("album", "song", "song", "Alex", "Other")),
            tags = listOf(tag("song", a)))
        val item = res.identities[ArtistExactOccurrenceKey("album", "song")]!!
        assertNull(item.confirmedMbid)
        assertEquals(listOf(a), item.untrustedTagMbids)
    }
    @Test fun albumBindingCannotAuthorizeGuestCreditedSong() {
        val res = ready(listOf(claim("album", "song", "song", "Alex", "Other")),
            binds = listOf(binding("album", a)))
        assertNull(res.identities.values.single().confirmedMbid)
    }
    @Test fun confirmedAlbumMbidDoesNotLeakToAnotherAlbum() {
        val res = ready(listOf(claim("one", "1"), claim("two", "2")), listOf(binding("one")))
        assertNull(res.identities[ArtistExactOccurrenceKey("two", "2")]?.confirmedMbid)
    }
    @Test fun uppercaseNameDoesNotSplitSameAlbumBinding() {
        val res = ready(listOf(claim("one", "1", credit = "ALEX", albumCredit = "alex")),
            listOf(binding("one")))
        assertEquals(a, res.identities.values.single().confirmedMbid)
    }
    @Test fun rawArtistMbidCannotOverrideConfirmedChoice() {
        val res = ready(listOf(claim("one", "1")), listOf(binding("one", a)), listOf(tag("1", b)))
        assertNull(res.identities.values.single().confirmedMbid)
        assertEquals(listOf(b), res.identities.values.single().untrustedTagMbids)
    }
    @Test fun matchingArtistTagDoesNotSuppressExplicitAlbumBinding() {
        val res = ready(listOf(claim("one", "1")), listOf(binding("one", a)), listOf(tag("1", a)))
        assertEquals(a, res.identities.values.single().confirmedMbid)
    }
    @Test fun multipleConflictingArtistTagMbidsSuppressAlbumIdentity() {
        val res = ready(listOf(claim("one", "1")), listOf(binding("one", a)),
            listOf(tag("1", a), ArtistTrackEvidenceRecord("lib", "1", "ARTIST_MBID", 1, b, 100L)))
        assertNull(res.identities.values.single().confirmedMbid)
        assertEquals(listOf(a, b), res.identities.values.single().untrustedTagMbids)
    }
    @Test fun autoTagRemainsObservationNotIdentity() {
        val res = ready(listOf(claim("one", "1")), tags = listOf(tag("1", a, "AUTO_ARTIST_MBID")))
        assertNull(res.identities.values.single().confirmedMbid)
    }
    @Test fun malformedTagNotAuthority() {
        val res = ready(listOf(claim("one", "1")), tags = listOf(tag("1", "not-uuid")))
        assertEquals(emptyList<String>(), res.identities.values.single().untrustedTagMbids)
    }
    @Test fun duplicateBindingCannotPickFirst() {
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.DUPLICATE_BINDING,
            blocked(listOf(claim("one", "1")), listOf(binding("one", a), binding("one", b))).reason)
    }
    @Test fun invalidBindingUuidFailsClosed() {
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.INVALID_CONFIRMED_IDENTITY,
            blocked(listOf(claim("one", "1")), listOf(binding("one", "no"))).reason)
    }
    @Test fun foreignBindingFailsClosed() {
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.CROSS_LIBRARY_EVIDENCE,
            blocked(listOf(claim("one", "1")), listOf(binding("one", lib = "other"))).reason)
    }
    @Test fun foreignTagFailsClosed() {
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.CROSS_LIBRARY_EVIDENCE,
            blocked(listOf(claim("one", "1")), tags = listOf(tag("1", lib = "other"))).reason)
    }
    @Test fun unsupportedBindingEvidenceRejected() {
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.UNKNOWN_EVIDENCE,
            blocked(listOf(claim("one", "1")), listOf(binding("one", evidence = "AUTO_ARTIST_MBID"))).reason)
    }
    @Test fun zeroOrAllLibraryNotAuthorized() {
        assertTrue(ArtistExactOccurrenceIdentityPolicy.reconcile(null, listOf(claim("a", "1")),
            emptyList(), emptyList()) is ArtistExactOccurrenceIdentityResult.Blocked)
        assertTrue(ArtistExactOccurrenceIdentityPolicy.reconcile("SCOPE_ALL", listOf(claim("a", "1")),
            emptyList(), emptyList()) is ArtistExactOccurrenceIdentityResult.Blocked)
    }
    @Test fun duplicateSameTrackIdentityRejected() {
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.DUPLICATE_OCCURRENCE,
            blocked(listOf(claim("one", "same"), claim("one", "same"))).reason)
    }
    @Test fun duplicateTrackIdAcrossAlbumsIsNotUsedAsMembershipAuthority() {
        val res = blocked(listOf(claim("one", "a", "same"), claim("two", "b", "same")),
            listOf(binding("two", b)))
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.TRACK_ID_COLLISION, res.reason)
    }
    @Test fun applyToClearsStaleCallerEvidence() {
        val res = ready(listOf(claim("one", "1")), listOf(binding("one", a)))
        val originals = listOf(ArtistMembershipOccurrence("lib", "one", "Alex", "1", b),
            ArtistMembershipOccurrence("lib", "two", "Alex", "2", b))
        val converted = res.applyTo(originals)
        assertEquals(a, converted[0].verifiedMbid)
        assertNull(converted[1].verifiedMbid)
    }
    @Test fun orderCannotChangeTwoAlbumPartition() {
        val claims = listOf(claim("one", "a"), claim("two", "b"))
        val rows = listOf(binding("one", a), binding("two", b))
        assertEquals(ready(claims, rows), ready(claims.reversed(), rows.reversed()))
    }
    @Test fun moreThanBudgetFailsClosed() {
        val claims = (0..ArtistExactOccurrenceIdentityPolicy.MAX_OCCURRENCES)
            .map { claim("one", "song$it") }
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.TOO_MANY_ROWS,
            blocked(claims).reason)
    }
    @Test fun unresolvedEvidenceClearsPriorIdentityAndExactConfirmation() {
        val original = ArtistMembershipOccurrence("lib", "one", "Alex", "1", b)
            .copy(exactTrackConfirmed = true)
        val reset = ArtistExactOccurrenceIdentityLoad(ready = false, error = false)
            .applyTo(listOf(original)).single()
        assertNull(reset.verifiedMbid)
        assertTrue(!reset.exactTrackConfirmed)
    }

    @Test fun pagedReadBeyondOldCeiling() = kotlinx.coroutines.runBlocking {
        val claims = (0 until 20_000).map { claim("album${it / 100}", "track$it") }
        var calls = 0
        val resolved = ArtistExactOccurrenceIdentityPolicy.reconcilePages("lib", claims) { page ->
            calls++
            assertTrue(page.size <= ArtistExactOccurrenceIdentityPolicy.PAGE_SIZE)
            ready(page)
        } as ArtistExactOccurrenceIdentityResult.Ready
        assertEquals(20_000, resolved.identities.size)
        assertEquals(79, calls)
    }
    @Test fun crossPageCollisionBlocksBeforeIo() = kotlinx.coroutines.runBlocking {
        val claims = (0 until 600).map { claim("album$it", "key$it", if (it == 599) "id0" else "id$it") }
        val resolved = ArtistExactOccurrenceIdentityPolicy.reconcilePages("lib", claims) { error("must not read") }
        assertEquals(ArtistExactOccurrenceIdentityResult.Blocked(ArtistExactOccurrenceIdentityResult.Reason.TRACK_ID_COLLISION), resolved)
    }
    @Test fun crossPageDuplicateOccurrenceBlocksBeforeIo() = kotlinx.coroutines.runBlocking {
        val claims = (0 until 600).map { claim("album", "key$it") }
        val resolved = ArtistExactOccurrenceIdentityPolicy.reconcilePages("lib", claims + claims.first()) { error("must not read") }
        assertEquals(ArtistExactOccurrenceIdentityResult.Blocked(ArtistExactOccurrenceIdentityResult.Reason.DUPLICATE_OCCURRENCE), resolved)
    }
    @Test fun latePageFailureNeverReturnsPartialMap() = kotlinx.coroutines.runBlocking {
        val claims = (0 until 600).map { claim("album", "key$it") }
        val failure = ArtistExactOccurrenceIdentityResult.Blocked(ArtistExactOccurrenceIdentityResult.Reason.UNKNOWN_EVIDENCE)
        var calls = 0
        val result = ArtistExactOccurrenceIdentityPolicy.reconcilePages("lib", claims) { page ->
            if (++calls == 2) failure else ready(page)
        }
        assertEquals(failure, result)
        assertEquals(2, calls)
    }
    @Test fun rejectsIncompletePage() = kotlinx.coroutines.runBlocking {
        val result = ArtistExactOccurrenceIdentityPolicy.reconcilePages("lib", listOf(claim("album", "key"))) {
            ArtistExactOccurrenceIdentityResult.Ready(emptyMap())
        }
        assertEquals(ArtistExactOccurrenceIdentityResult.Blocked(ArtistExactOccurrenceIdentityResult.Reason.UNKNOWN_EVIDENCE), result)
    }
    @Test fun pagedMatchesSingleReconcileWithSharedAlbumBinding() = kotlinx.coroutines.runBlocking {
        val claims = (0 until 600).map { claim("album", "key$it") }
        val binds = listOf(binding("album"))
        val tags = listOf(tag("key257"))
        val result = ArtistExactOccurrenceIdentityPolicy.reconcilePages("lib", claims) { page ->
            ready(page, binds, tags.filter { tag -> page.any { it.trackId == tag.trackId } })
        }
        assertEquals(ready(claims, binds, tags), result)
    }
    @Test fun textBudgetRejectsBeforeIo() = kotlinx.coroutines.runBlocking {
        val oversized = claim("album", "key").copy(localContentSignature = "x".repeat(16_777_217))
        val result = ArtistExactOccurrenceIdentityPolicy.reconcilePages("lib", listOf(oversized)) { error("must not read") }
        assertEquals(ArtistExactOccurrenceIdentityResult.Blocked(ArtistExactOccurrenceIdentityResult.Reason.TOO_MANY_ROWS), result)
    }
}
