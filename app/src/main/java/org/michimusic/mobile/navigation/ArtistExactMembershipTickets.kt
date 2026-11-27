package org.michimusic.mobile.navigation

import org.michimusic.mobile.artist.ArtistExactMembershipResult
import org.michimusic.mobile.artist.ArtistExactMembershipProjection
import org.michimusic.mobile.artist.ArtistExactMembershipSnapshot
import org.michimusic.mobile.artist.ArtistMembershipOccurrence
import org.michimusic.mobile.artist.ArtistPresentationEntry
import java.util.UUID
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.mobile.library.ArtistTrackSessionProof
import org.michimusic.mobile.library.ArtistTrackSessionProofBudget
import org.michimusic.mobile.library.ArtistTrackChoiceAuthority

/**
 * D33: route URLs carry ONLY a random token. The scope, album membership and the
 * possibly private track URIs never leave this bounded in-process store.
 * No ticket grants identity association, profile writes, artist photos or HTTP.
 */
internal class ArtistExactMembershipTicketStore(
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Entry(
        val snapshot: ArtistExactMembershipSnapshot,
        val root: ArtistReviewRootGuard,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
        val proofs: List<ArtistTrackSessionProof>,
    )
    private val tickets = LinkedHashMap<String, Entry>()
    private val changes = MutableStateFlow(0L)
    val validityChanges = changes.asStateFlow()

    private fun remove(token: String) {
        if (tickets.remove(token) != null) changes.value += 1
    }

    @Synchronized
    fun issue(
        root: ArtistReviewRootGuard,
        libraryId: String,
        entry: ArtistPresentationEntry,
        occurrences: List<ArtistMembershipOccurrence>,
        sessionProofs: Collection<ArtistTrackSessionProof> = emptyList(),
    ): String? {
        // Issue only from a verified local projection, never from a caller-created snapshot.
        val snapshot = (ArtistExactMembershipProjection.prepare(entry, occurrences, libraryId)
            as? ArtistExactMembershipResult.Ready)?.snapshot ?: return null
        val now = clockMs()
        if (now < 0 || now > Long.MAX_VALUE - TTL_MS) return null
        evictExpired(now)
        // Same-credit partitions can require sibling proofs to reconstruct the exact grid.
        // Do not transport unrelated credits, libraries, or unbounded input.
        val authority = ArtistTrackChoiceAuthority(libraryId, root.rootStamp.sourceId,
            root.rootStamp.epoch, root.catalogRevision)
        val exact = occurrences.filter { it.libraryId == libraryId &&
            it.literalCredit in snapshot.mergedCredits }.groupBy { it.albumId to it.trackIdentity }
        val proofs = sessionProofs.asSequence().filter { proof ->
            val row = exact[proof.claim.albumId to proof.claim.trackIdentity]?.singleOrNull()
            proof.authority == authority && proof.claim.libraryId == libraryId &&
                row?.literalCredit == proof.claim.literalCredit && row.exactTrackConfirmed &&
                row.verifiedMbid == proof.confirmedMbid
        }.distinct().take(ArtistTrackSessionProofBudget.MAX_PROOFS + 1).toList()
        // Never silently discard required proofs and issue an unusable ticket.
        if (!ArtistTrackSessionProofBudget.fits(proofs) || proofs.groupBy { it.claim.albumId to it.claim.trackIdentity }
                .values.any { it.size != 1 }) return null
        // Charge retained proof references conservatively across the entire ticket pool.
        // Eviction is observable; it never deletes the source session's evidence.
        while (tickets.size >= MAX_TICKETS || !ArtistTrackSessionProofBudget.fitsTicketPool(
                tickets.values.flatMap { it.proofs } + proofs)) {
            if (tickets.isEmpty()) return null
            remove(tickets.keys.first())
        }
        val token = UUID.randomUUID().toString()
        tickets[token] = Entry(snapshot, root, now, now + TTL_MS, proofs)
        return token
    }

    /**
     * On arrival always require the current exact library, root generation AND a
     * fresh projection from the active catalog. Stale/replayed membership fails closed.
     */
    @Synchronized
    fun resolve(
        token: String,
        root: ArtistReviewRootGuard,
        libraryId: String,
        entry: ArtistPresentationEntry,
        occurrences: List<ArtistMembershipOccurrence>,
    ): ArtistExactMembershipSnapshot? {
        if (!TOKEN.matches(token)) return null
        val now = clockMs()
        evictExpired(now)
        val ticket = tickets[token] ?: return null
        if (now < ticket.issuedAtMs || root != ticket.root || libraryId != ticket.snapshot.libraryId) {
            remove(token)
            return null
        }
        val current = ArtistExactMembershipProjection.prepare(entry, occurrences, libraryId)
        if (current != ArtistExactMembershipResult.Ready(ticket.snapshot)) {
            remove(token)
            return null
        }
        return ticket.snapshot
    }

    /** A preview only provides a display name, not authority to load data or mutate it. */
    @Synchronized
    fun preview(token: String, root: ArtistReviewRootGuard): ArtistExactMembershipSnapshot? {
        if (!TOKEN.matches(token)) return null
        val now = clockMs()
        evictExpired(now)
        val ticket = tickets[token] ?: return null
        if (now < ticket.issuedAtMs || ticket.root != root) {
            remove(token)
            return null
        }
        return ticket.snapshot
    }

    /** Only a current opaque ticket can supply candidates for fresh Room revalidation. */
    @Synchronized
    fun sessionProofs(token: String, root: ArtistReviewRootGuard, libraryId: String): List<ArtistTrackSessionProof> {
        val snapshot = preview(token, root) ?: return emptyList()
        if (snapshot.libraryId != libraryId) return emptyList()
        return tickets[token]?.proofs.orEmpty().toList()
    }

    /** Rebuild from the CURRENT grid algorithm and validate exactly once against the ticket. */
    @Synchronized
    fun resolveAgainstCatalog(
        token: String,
        root: ArtistReviewRootGuard,
        libraryId: String,
        entries: List<ArtistPresentationEntry>,
        occurrences: List<ArtistMembershipOccurrence>,
    ): ArtistExactMembershipSnapshot? {
        val display = preview(token, root) ?: return null
        val matches = entries.filter {
            it.libraryId == libraryId && it.credit == display.displayCredit &&
                it.mergedCredits.toSet() == display.mergedCredits.toSet() &&
                it.mbid?.lowercase() == display.verifiedMbid
        }
        if (matches.size != 1) {
            remove(token)
            return null
        }
        return resolve(token, root, libraryId, matches.single(), occurrences)
    }

    /** Monotonic remaining lifetime for an open screen, not an authorization. */
    @Synchronized
    fun remainingLifetimeMs(token: String, root: ArtistReviewRootGuard): Long {
        if (preview(token, root) == null) return 0L
        val ticket = tickets[token] ?: return 0L
        val now = clockMs()
        if (now < ticket.issuedAtMs || now >= ticket.expiresAtMs) {
            remove(token)
            return 0L
        }
        return ticket.expiresAtMs - now
    }

    @Synchronized
    fun revoke(token: String) { remove(token) }

    private fun evictExpired(now: Long) {
        val expired = tickets.filterValues { it.expiresAtMs <= now || now < it.issuedAtMs }.keys.toList()
        expired.forEach(::remove)
    }

    companion object {
        private const val MAX_TICKETS = 32
        private const val TTL_MS = 20L * 60 * 1000
        private val TOKEN = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$")
    }
}

internal val ArtistExactMembershipTickets = ArtistExactMembershipTicketStore()
