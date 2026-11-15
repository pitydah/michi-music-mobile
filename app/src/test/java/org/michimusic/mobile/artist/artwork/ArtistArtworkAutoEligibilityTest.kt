package org.michimusic.mobile.artist.artwork

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.data.artist.ArtistEnrichmentRecord
import org.michimusic.data.artist.DurableArtistAssociationRecord
import org.michimusic.mobile.artist.ArtistExternalProfile

class ArtistArtworkAutoEligibilityTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val association = DurableArtistAssociationRecord(
        libraryId = "lib", creditKey = "midnight oil", associationId = "stable",
        identityAssociationId = "identity", createdAtMs = 1, updatedAtMs = 1,
    )
    private fun record(qid: String? = "Q48284", mbidValue: String = mbid) = ArtistEnrichmentRecord(
        libraryId = "lib", creditKey = "midnight oil", albumGroupKey = "identity",
        confirmedMbid = mbid, profileJson = Json.encodeToString(
            ArtistExternalProfile(mbid = mbidValue, name = "Midnight Oil", wikidataQid = qid),
        ), savedAtMs = 1, staleAfterMs = Long.MAX_VALUE, source = "musicbrainz-manual-selection",
        biographySourceUrl = null, portraitSourceUrl = null, portraitLicense = null,
        portraitLicenseUrl = null, portraitAttribution = null,
    )
    private fun resolve(associations: List<DurableArtistAssociationRecord> = listOf(association),
        record: ArtistEnrichmentRecord? = record(), bindings: Int = 1,
        id: String = "stable"): ArtistIdentityAnchor? =
        ArtistArtworkAutoEligibility.anchor("lib", "midnight oil", id, associations, record, bindings)

    @Test fun exactBoundAssociationCreatesOnlyItsOwnAnchor() {
        val anchor = resolve()
        assertNotNull(anchor)
        assertEquals("stable", anchor!!.albumGroupKey)
        assertEquals(mbid, anchor.confirmedMbid)
        assertEquals("Q48284", anchor.verifiedQid)
    }

    @Test fun twoHomonymsCanResolveIndependentlyWhenAssociationIsExplicit() {
        val other = association.copy(associationId = "other", identityAssociationId = "identity-other")
        assertEquals("stable", resolve(associations = listOf(association, other))!!.albumGroupKey)
        assertEquals("other", resolve(associations = listOf(association, other),
            record = record().copy(albumGroupKey = "identity-other"), id = "other")!!.albumGroupKey)
    }

    @Test fun orphanMismatchedOrUnverifiedBindingIsRejected() {
        assertNull(resolve(bindings = 0))
        assertNull(resolve(id = "missing"))
        assertNull(resolve(record = record(qid = null)))
        assertNull(resolve(record = record(mbidValue = "11111111-1111-4111-8111-111111111111")))
        assertNull(resolve(record = record().copy(albumGroupKey = "wrong-identity")))
        assertNull(resolve(record = record().copy(source = "musicbrainz-manual-selection-pending")))
    }
}
