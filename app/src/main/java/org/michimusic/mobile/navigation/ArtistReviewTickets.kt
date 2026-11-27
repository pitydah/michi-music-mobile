package org.michimusic.mobile.navigation

import android.os.SystemClock
import org.michimusic.mobile.artist.ArtistVariantReviewChoice
import org.michimusic.data.repository.MusicRootStamp
import java.util.UUID

/** The persisted root epoch and the catalog revision are both required for a review. */
data class ArtistReviewRootGuard(val rootStamp: MusicRootStamp, val catalogRevision: Long)

/** Opaque in-process navigation context: no artist, library, MBID or album IDs in route URLs. */
data class ArtistReviewContext(
    val credit: String,
    val libraryId: String,
    val albumIds: List<String>,
    val verifiedMbidHint: String?,
    val rootGeneration: ArtistReviewRootGuard,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
) {
    /** The route grants no write authority: validate at presentation AND before each action. */
    fun permits(
        visibleCredit: String, visibleLibraryId: String?, visibleAlbums: List<String>,
        currentRoot: ArtistReviewRootGuard, nowMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean =
        nowMs >= issuedAtMs && nowMs < expiresAtMs &&
            rootGeneration == currentRoot &&
            credit == visibleCredit && libraryId == visibleLibraryId &&
            albumIds == visibleAlbums.distinct().sorted() && albumIds.isNotEmpty()
}

/** TTL and bounded retention are deliberate; a process-restarted back stack fails closed. */
internal class ArtistReviewTicketStore(private val clockMs: () -> Long = SystemClock::elapsedRealtime) {
    private data class Entry(val context: ArtistReviewContext, val expiresAtMs: Long)
    private val tickets = LinkedHashMap<String, Entry>()

    @Synchronized
    fun issue(choice: ArtistVariantReviewChoice, root: ArtistReviewRootGuard): String? {
        val libraryId = choice.libraryId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val credit = choice.credit.trim().takeIf { it.isNotEmpty() && it.length <= 160 } ?: return null
        val albums = choice.albumIds.distinct().sorted()
        if (choice.conflictingEvidence || albums.isEmpty() || albums.any { it.isBlank() } ||
            albums.size > 5000 || albums != choice.albumIds
        ) return null
        val now = clockMs()
        evictExpired(now)
        while (tickets.size >= MAX_TICKETS) tickets.remove(tickets.keys.first())
        val id = UUID.randomUUID().toString()
        tickets[id] = Entry(
            ArtistReviewContext(
                credit, libraryId, albums, choice.verifiedMbid,
                rootGeneration = root, issuedAtMs = now, expiresAtMs = now + TTL_MS,
            ),
            now + TTL_MS,
        )
        return id
    }

    @Synchronized
    fun resolve(id: String, currentRoot: ArtistReviewRootGuard): ArtistReviewContext? {
        if (!TICKET_FORMAT.matches(id)) return null
        val now = clockMs()
        evictExpired(now)
        val entry = tickets[id] ?: return null
        if (entry.context.rootGeneration != currentRoot || now < entry.context.issuedAtMs) {
            tickets.remove(id)
            return null
        }
        return entry.context
    }

    @Synchronized
    fun revoke(id: String) { tickets.remove(id) }

    private fun evictExpired(now: Long) { tickets.entries.removeAll { it.value.expiresAtMs <= now } }

    companion object {
        const val TTL_MS = 20L * 60L * 1000L
        private const val MAX_TICKETS = 64
        private val TICKET_FORMAT = Regex("^[a-f0-9-]{36}$")
    }
}

internal val ArtistReviewTickets = ArtistReviewTicketStore()
