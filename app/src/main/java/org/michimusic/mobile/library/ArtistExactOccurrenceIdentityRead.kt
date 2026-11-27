package org.michimusic.mobile.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import androidx.room.InvalidationTracker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.data.artist.ArtistConfirmedAlbumIdentityRow
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.data.artist.ArtistTrackEvidenceRecord
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.core.models.Track
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import org.michimusic.mobile.artist.ArtistMembershipOccurrence
import org.michimusic.mobile.artist.exactArtistTrackIdentity

/** Catalogue proof usable after cold restart; no local path is persisted as plain text. */
internal fun artistTrackContentSignature(track: Track): String =
    listOf(track.contentHash, track.size.toString(), track.dateAdded.toString(),
        track.duration.toString(), track.title, track.format, track.artist, track.albumArtist)
        .joinToString("\u0000")

/** A scoped, read-only snapshot: no profile writes, network, or embedded tag scan. */
internal data class ArtistExactOccurrenceIdentityLoad(
    val ready: Boolean,
    val error: Boolean,
    val result: ArtistExactOccurrenceIdentityResult.Ready? = null,
) {
    fun applyTo(rows: List<ArtistMembershipOccurrence>): List<ArtistMembershipOccurrence> =
        result?.applyTo(rows) ?: rows.map { it.copy(verifiedMbid = null, exactTrackConfirmed = false) }
}

/** One read transaction, globally admitted claims and bounded per-page evidence. */
internal class ArtistExactOccurrenceIdentityRoomReader(
    private val database: ArtistEnrichmentCacheDatabase,
) {
    suspend fun read(
        libraryId: String,
        claims: List<ArtistExactOccurrenceClaim>,
        rootSourceId: String,
        rootEpoch: Long,
        catalogRevision: Long,
        sessionProofs: Collection<ArtistTrackSessionProof> = emptyList(),
    ): ArtistExactOccurrenceIdentityResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val dao = database.artistEnrichmentDao()
            val authority = ArtistTrackChoiceAuthority(libraryId, rootSourceId, rootEpoch, catalogRevision)
            // Check the WHOLE proof collection before filtering; duplicate/overbudget receipts
            // cannot become admissible simply because they happen to fall on separate pages.
            val proofs = if (ArtistTrackSessionProofBudget.fits(sessionProofs)) {
                sessionProofs.associateBy { ArtistExactOccurrenceKey(it.claim.albumId, it.claim.trackIdentity) }
                    .takeIf { it.size == sessionProofs.size }.orEmpty()
            } else emptyMap()
            ArtistExactOccurrenceIdentityPolicy.reconcilePages(libraryId, claims) { page ->
                val albumKeys = page.map { it.albumId }.distinct().sorted()
                val creditsByAlbum = page.groupBy { it.albumId }.mapValues { (_, rows) ->
                    rows.map { it.literalCredit.trim().lowercase(java.util.Locale.ROOT) }.distinct().sorted()
                }
                val validPairs = page.map { it.albumId to it.literalCredit.trim().lowercase(java.util.Locale.ROOT) }.toHashSet()
                val bindings = mutableListOf<ArtistConfirmedAlbumIdentityRow>()
                val tags = mutableListOf<ArtistTrackEvidenceRecord>()
                val choices = mutableListOf<org.michimusic.data.artist.ArtistExplicitTrackIdentityRecord>()
                fun overflow() = ArtistExactOccurrenceIdentityResult.Blocked(ArtistExactOccurrenceIdentityResult.Reason.TOO_MANY_ROWS)
                for (albums in albumKeys.chunked(48)) {
                    val pageCredits = albums.flatMap { creditsByAlbum[it].orEmpty() }.distinct().sorted()
                    for (credits in pageCredits.chunked(48)) {
                        val maxRows = albums.size * credits.size
                        val rows = dao.getConfirmedAlbumIdentityRows(libraryId, albums, credits, maxRows + 1)
                        if (rows.size > maxRows) return@reconcilePages overflow()
                        bindings += rows.filter { (it.albumKey to it.creditKey) in validPairs }
                    }
                }
                for (tracks in page.mapNotNull { it.trackId }.distinct().sorted().chunked(64)) {
                    // 2 allowed fields x 32 ordinals per track. Extra rows are corruption,
                    // never silently truncated. Choices may include obsolete album keys.
                    val maxTags = tracks.size * 64
                    val tagRows = dao.getUntrustedArtistTagMbidsForTracks(libraryId, tracks, maxTags + 1)
                    val choiceRows = dao.explicitTrackIdentities(libraryId, tracks, 4097)
                    if (tagRows.size > maxTags || choiceRows.size > 4096) return@reconcilePages overflow()
                    tags += tagRows
                    choices += choiceRows
                }
                val reconciled = ArtistExactOccurrenceIdentityPolicy.reconcile(
                    libraryId, page, bindings, tags, choices, rootSourceId, rootEpoch, catalogRevision)
                if (reconciled is ArtistExactOccurrenceIdentityResult.Ready) {
                    val relevant = page.mapNotNull { proofs[ArtistExactOccurrenceKey(it.albumId, it.trackIdentity)] }
                    ArtistTrackSessionProofPolicy.apply(reconciled, page, relevant, authority, choices)
                } else reconciled
            }
        }
    }
}

/**
 * The source is the current canonical library snapshot, not a previous search result.
 * The root generation and revision are checked before/after the Room transaction.
 * `All` may display names but cannot establish exact ownership for this read.
 */
@Composable
internal fun rememberArtistExactOccurrenceIdentities(
    libraryId: String?,
    albums: List<LocalAlbum>,
    rootAuthority: MusicRootAuthority,
    exactOneLibrary: Boolean,
    retryKey: Int = 0,
    sessionProofs: Collection<ArtistTrackSessionProof> = emptyList(),
): ArtistExactOccurrenceIdentityLoad {
    val context = LocalContext.current
    val revision by rootAuthority.revision.collectAsStateWithLifecycle()
    val stamp by rootAuthority.reviewStamp.collectAsStateWithLifecycle()
    val database = remember(context) { ArtistEnrichmentPersistence.database(context) }
    val proofs = sessionProofs.toList()
    return key(database, libraryId, albums, exactOneLibrary, stamp, revision, retryKey, proofs) {
        val loaded by produceState(ArtistExactOccurrenceIdentityLoad(ready = false, error = false)) {
            if (!exactOneLibrary || libraryId.isNullOrBlank()) {
                value = ArtistExactOccurrenceIdentityLoad(ready = true, error = true)
                return@produceState
            }
            try {
                val claims = withContext<List<ArtistExactOccurrenceClaim>>(Dispatchers.Default) {
                    if (albums.sumOf { maxOf(1, it.tracks.size).toLong() } >
                        ArtistExactOccurrenceIdentityPolicy.MAX_OCCURRENCES) return@withContext emptyList()
                    val snapshot = ArrayList<ArtistExactOccurrenceClaim>()
                    var textUnits = 0L
                    fun append(claim: ArtistExactOccurrenceClaim): Boolean {
                        textUnits += ArtistExactOccurrenceIdentityPolicy.claimTextUnits(claim)
                        if (textUnits > ArtistExactOccurrenceIdentityPolicy.MAX_CLAIM_TEXT_UNITS) return false
                        snapshot.add(claim)
                        return true
                    }
                    for (group in albums) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val albumId = group.album.id
                        val albumCredit = group.album.artist
                        if (group.tracks.isEmpty()) {
                            if (!append(ArtistExactOccurrenceClaim(libraryId, albumId, albumCredit,
                                albumCredit, "$albumId#album", null))) return@withContext emptyList()
                        } else for (track in group.tracks) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (!append(ArtistExactOccurrenceClaim(libraryId, albumId, albumCredit,
                                track.artist.ifBlank { albumCredit }, exactArtistTrackIdentity(track),
                                track.id, artistTrackContentSignature(track)))) return@withContext emptyList()
                        }
                    }
                    snapshot
                }
                if (claims.isEmpty()) {
                    value = ArtistExactOccurrenceIdentityLoad(ready = true, error = true)
                    return@produceState
                }
                // Register before initial read so edits between registration and read cannot be lost.
                artistIdentityInvalidations(database).collectLatest {
                    value = ArtistExactOccurrenceIdentityLoad(ready = false, error = false)
                    val before = rootAuthority.currentReviewStamp()
                    if (before != stamp || rootAuthority.revision.value != revision) {
                        value = ArtistExactOccurrenceIdentityLoad(ready = true, error = true)
                        return@collectLatest
                    }
                    val read = ArtistExactOccurrenceIdentityRoomReader(database).read(
                        libraryId, claims, before.sourceId, before.epoch, revision, proofs)
                    val after = rootAuthority.currentReviewStamp()
                    value = if (before != after || after != stamp || rootAuthority.revision.value != revision ||
                        read !is ArtistExactOccurrenceIdentityResult.Ready
                    ) ArtistExactOccurrenceIdentityLoad(ready = true, error = true)
                    else ArtistExactOccurrenceIdentityLoad(ready = true, error = false, result = read)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = ArtistExactOccurrenceIdentityLoad(ready = true, error = true)
            }
        }
        loaded
    }
}

/** Read-only observer, scoped to this composition and the evidence tables actually queried. */
private fun artistIdentityInvalidations(database: ArtistEnrichmentCacheDatabase) = callbackFlow<Unit> {
    val tracker = database.invalidationTracker
    val observer = object : InvalidationTracker.Observer(
        "artist_album_credit_binding", "durable_artist_association", "artist_enrichment",
        "artist_track_evidence", "artist_explicit_track_identity",
    ) {
        override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
    }
    tracker.addObserver(observer)
    trySend(Unit)
    awaitClose { tracker.removeObserver(observer) }
}.buffer(Channel.CONFLATED).flowOn(Dispatchers.IO)
