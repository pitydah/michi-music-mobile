package org.michimusic.mobile.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.mobile.artist.ArtistEnrichmentSelectionStore
import org.michimusic.mobile.artist.canonicalArtistMbid
import org.michimusic.mobile.artist.artistCreditDiscoveryKey

/**
 * K48-R12-03 — corroborated identities that already live on this device.
 *
 * The artists grid may only merge two credits when a MusicBrainz identity backs both,
 * and it may never consult a provider to find out. So identities are read from the local
 * enrichment store, scoped to the exact library, and only for credits that actually have a
 * look-alike sibling: without ambiguity there is nothing to resolve.
 *
 * [ready] is false until the first read finishes, so the grid can avoid rendering a
 * placement it may have to retract. A failing read resolves to [error] with no identities
 * at all — every credit then stays unverified, which is the safe direction.
 */
data class VerifiedArtistIdentities(
    val ready: Boolean,
    val mbidsByCredit: Map<String, String>,
    val error: Boolean = false,
)

/**
 * Only credits that look alike inside the same scope can be merged, so only they need an
 * identity read. Everything else stays unresolved without touching the store.
 */
fun identityLookupCandidates(credits: Map<String, List<String>>): Map<String, List<String>> {
    val byDiscoveryKey = credits.keys.groupBy { artistCreditDiscoveryKey(it) }
    return credits.filterKeys { key ->
        byDiscoveryKey[artistCreditDiscoveryKey(key)].orEmpty().size > 1
    }
}

/**
 * Loads the corroborated MBID for every credit that shares a discovery key with another
 * credit in the same library scope.
 *
 * @param libraryId exact library the credits were observed in; null (aggregate view)
 *   authorises no identity lookup, because an association belongs to one library.
 * @param credits credit to the album ids it was observed on, already trimmed.
 */
@Composable
fun rememberVerifiedArtistMbids(
    libraryId: String?,
    credits: Map<String, List<String>>,
    store: ArtistEnrichmentSelectionStore,
    retryKey: Int = 0,
): VerifiedArtistIdentities {
    val candidates = identityLookupCandidates(credits)
    val observed by produceState(
        initialValue = VerifiedArtistIdentities(ready = false, mbidsByCredit = emptyMap()),
        libraryId,
        candidates,
        store,
        retryKey,
    ) {
        if (libraryId == null || candidates.isEmpty()) {
            value = VerifiedArtistIdentities(ready = true, mbidsByCredit = emptyMap())
            return@produceState
        }
        try {
            val resolved = withContext(Dispatchers.IO) {
                buildMap {
                    for ((credit, albumIds) in candidates) {
                        val persisted = store.load(libraryId, credit, albumIds) ?: continue
                        // A pending album binding is not corroboration: the association is
                        // still being confirmed, so it may not authorise a merge.
                        if (!persisted.profilePersisted || persisted.needsConfirmation) continue
                        val mbid = canonicalArtistMbid(persisted.profile.mbid) ?: continue
                        put(credit, mbid)
                    }
                }
            }
            value = VerifiedArtistIdentities(ready = true, mbidsByCredit = resolved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fail closed: an unreadable identity store verifies nobody.
            value = VerifiedArtistIdentities(ready = true, mbidsByCredit = emptyMap(), error = true)
        }
    }
    return observed
}
