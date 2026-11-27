package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.artist.ArtistExplicitTrackIdentityRecord

/** D48 pure policy tests: must also run within Gradle on the true repository. */
class ArtistOnDemandTrackRevalidationTest {
    private val mbid = "11111111-1111-4111-8111-111111111111"
    private val sha = "a".repeat(64)
    private val claim = ArtistExactOccurrenceClaim(
        "device", "album", "Alex", "Alex", "device\u0000song\u0000/file", "song",
        "\u000042\u0000unhashed",
    )
    private val auth = ArtistTrackChoiceAuthority("device", "device_music_root", 0, 1)
    private val row = ArtistExplicitTrackIdentityRecord(
        "device", "album", "song", "alex", ArtistExplicitTrackChoicePolicy.trackDigest(claim),
        mbid, "device_music_root", 0, 0,
        ArtistExplicitTrackChoicePolicy.EVIDENCE, 1, 1000, sha, 42,
    )
    private val key = ArtistExactOccurrenceKey(claim.albumId, claim.trackIdentity)
    private val original = ArtistExactOccurrenceIdentityResult.Ready(
        mapOf(key to ArtistExactOccurrenceIdentity(null, listOf(mbid))),
    )
    private val receipt = ArtistTrackSessionProof(claim, mbid, sha, 42, auth, row.revision, row.confirmedAtEpochMs)

    @Test fun exactSavedRowMayBeRevalidatedWithoutCatalogSha() {
        assertTrue(ArtistTrackSessionProofPolicy.matches(row, claim, auth))
        assertFalse(ArtistExplicitTrackChoicePolicy.matches(row, claim, auth.sourceId,
            auth.rootEpoch, auth.catalogRevision))
    }

    @Test fun originalRoomResultIsNeverMutated() {
        val updated = ArtistTrackSessionProofPolicy.apply(original, listOf(claim), listOf(receipt), auth, listOf(row))
        assertEquals(mbid, updated.identities[key]?.confirmedMbid)
        assertEquals(true, updated.identities[key]?.confirmedByExactTrackChoice)
        assertNull(original.identities[key]?.confirmedMbid)
    }

    @Test fun changedRootOrCatalogRevokesSessionReceipt() {
        assertEquals(original, ArtistTrackSessionProofPolicy.apply(original, listOf(claim),
            listOf(receipt), auth.copy(rootEpoch = 2), listOf(row)))
        assertEquals(original, ArtistTrackSessionProofPolicy.apply(original, listOf(claim),
            listOf(receipt), auth.copy(catalogRevision = 2), listOf(row)))
    }

    @Test fun changedClaimDoesNotInheritSessionReceipt() {
        assertEquals(original, ArtistTrackSessionProofPolicy.apply(original,
            listOf(claim.copy(trackIdentity = "replacement")), listOf(receipt), auth, listOf(row)))
    }

    @Test fun invalidProofNeverPromotesIdentity() {
        assertEquals(original, ArtistTrackSessionProofPolicy.apply(original, listOf(claim),
            listOf(receipt.copy(sourceContentSha256 = "")), auth, listOf(row)))
        assertEquals(original, ArtistTrackSessionProofPolicy.apply(original, listOf(claim),
            listOf(receipt.copy(confirmedMbid = "not-an-mbid")), auth, listOf(row)))
    }

    @Test fun duplicateReceiptsFailClosed() {
        assertEquals(original, ArtistTrackSessionProofPolicy.apply(original, listOf(claim),
            listOf(receipt, receipt), auth, listOf(row)))
    }

    @Test fun changedStoredRowCannotBeReused() {
        assertFalse(ArtistTrackSessionProofPolicy.matches(row.copy(revision = 0), claim, auth))
        assertFalse(ArtistTrackSessionProofPolicy.matches(row.copy(rootEpoch = 3), claim, auth))
        assertFalse(ArtistTrackSessionProofPolicy.matches(row.copy(sourceContentSha256 = ""), claim, auth))
        assertFalse(ArtistTrackSessionProofPolicy.matches(row.copy(trackKeySha256 = "0".repeat(64)), claim, auth))
    }
    @Test fun missingOrChangedRoomRowNeverReplaysProof() {
        val variants = listOf(emptyList(), listOf(row.copy(revision = 2)),
            listOf(row.copy(confirmedAtEpochMs = 1001)),
            listOf(row.copy(sourceContentSha256 = "b".repeat(64))),
            listOf(row, row), listOf(row.copy(confirmedMbid = "22222222-2222-4222-8222-222222222222")))
        variants.forEach { rows ->
            assertEquals(original, ArtistTrackSessionProofPolicy.apply(original, listOf(claim),
                listOf(receipt), auth, rows))
        }
    }

    @Test fun proofBudgetAccepts129AndNeverEvictsPreviousOnRefusal() {
        val proofs = (0..128).map { i -> receipt.copy(claim = claim.copy(trackIdentity = "track$i")) }
        var cache = emptyMap<ArtistExactOccurrenceKey, ArtistTrackSessionProof>()
        for (proof in proofs) cache = requireNotNull(ArtistTrackSessionProofBudget.add(cache, proof, auth))
        assertEquals(129, cache.size)
        assertEquals(proofs.toSet(), cache.values.toSet())
        assertNull(ArtistTrackSessionProofBudget.add(cache, receipt.copy(claim = claim.copy(
            localContentSignature = "x".repeat(2_100_000))), auth))
        assertEquals(129, cache.size)
    }
    @Test fun proofBudgetReplacesExactKeyAndRejectsWrongAuthority() {
        val cache = requireNotNull(ArtistTrackSessionProofBudget.add(emptyMap(), receipt, auth))
        val newer = receipt.copy(rowRevision = 2)
        assertEquals(listOf(newer), ArtistTrackSessionProofBudget.add(cache, newer, auth)?.values?.toList())
        assertNull(ArtistTrackSessionProofBudget.add(cache, receipt, auth.copy(rootEpoch = 5)))
        val newScope = receipt.copy(authority = auth.copy(rootEpoch = 5))
        assertEquals(listOf(newScope), ArtistTrackSessionProofBudget.add(cache, newScope, newScope.authority)?.values?.toList())
    }
    @Test fun moreThan128ReceiptsStillApplyWithCurrentRows() {
        val claims = (0..128).map { i -> claim.copy(trackId = "id$i", trackIdentity = "track$i") }
        val rows = claims.map { c -> row.copy(trackId = requireNotNull(c.trackId),
            trackKeySha256 = ArtistExplicitTrackChoicePolicy.trackDigest(c)) }
        val proofs = claims.map { c -> receipt.copy(claim = c) }
        val original = ArtistExactOccurrenceIdentityResult.Ready(claims.associate {
            ArtistExactOccurrenceKey(it.albumId, it.trackIdentity) to ArtistExactOccurrenceIdentity(null, listOf(mbid)) })
        val applied = ArtistTrackSessionProofPolicy.apply(original, claims, proofs, auth, rows)
        assertEquals(129, applied.identities.values.count { it.confirmedByExactTrackChoice })
    }

    @Test fun countBudgetAndAggregateTicketBudgetAreBounded() {
        val max = ArtistTrackSessionProofBudget.MAX_PROOFS
        assertTrue(ArtistTrackSessionProofBudget.fits(List(max) { receipt }))
        assertFalse(ArtistTrackSessionProofBudget.fits(List(max + 1) { receipt }))
        assertTrue(ArtistTrackSessionProofBudget.fitsTicketPool(List(max * 2) { receipt }))
        assertFalse(ArtistTrackSessionProofBudget.fitsTicketPool(List(max * 2 + 1) { receipt }))
    }

    @Test fun lateOrContradictoryProofCannotReplaceAcceptedRevision() {
        val newer = receipt.copy(rowRevision = 2)
        val cache = requireNotNull(ArtistTrackSessionProofBudget.add(emptyMap(), newer, auth))
        assertNull(ArtistTrackSessionProofBudget.add(cache, receipt, auth))
        assertNull(ArtistTrackSessionProofBudget.add(cache, newer.copy(sourceContentSha256 = "b".repeat(64)), auth))
        assertEquals(cache, ArtistTrackSessionProofBudget.add(cache, newer, auth))
    }
}
