package org.michimusic.mobile.artist.artwork

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.michimusic.data.artist.ArtistEnrichmentRecord
import org.michimusic.data.artist.DurableArtistAssociationRecord
import org.michimusic.mobile.artist.ArtistExternalProfile
import org.michimusic.mobile.artist.canonicalArtistMbid

/** Named association is selected by an exact Room album-credit binding, not by credit alone. */
internal object ArtistArtworkAutoEligibility {
    fun anchor(
        libraryId: String,
        creditKey: String,
        associationId: String,
        associations: List<DurableArtistAssociationRecord>,
        record: ArtistEnrichmentRecord?,
        confirmedBindings: Int,
    ): ArtistIdentityAnchor? {
        val association = associations.singleOrNull { it.associationId == associationId }
            ?: return null
        if (confirmedBindings < 1 || record == null) return null
        if (association.libraryId != libraryId || association.creditKey != creditKey ||
            record.libraryId != libraryId || record.creditKey != creditKey ||
            record.albumGroupKey != association.identityAssociationId ||
            record.source == "musicbrainz-manual-selection-pending"
        ) return null
        val mbid = canonicalArtistMbid(record.confirmedMbid) ?: return null
        if (record.confirmedMbid != mbid) return null
        val profile = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<ArtistExternalProfile>(record.profileJson)
        } catch (_: Exception) { return null }
        if (profile.mbid != mbid) return null
        val qid = profile.wikidataQid?.takeIf(::validWikidataQid) ?: return null
        if (association.associationId.isBlank() || creditKey.isBlank()) return null
        return ArtistIdentityAnchor(
            libraryId = libraryId,
            creditKey = creditKey,
            albumGroupKey = association.associationId,
            confirmedMbid = mbid,
            verifiedQid = qid,
            displayCredit = profile.name.take(120),
        )
    }
}
