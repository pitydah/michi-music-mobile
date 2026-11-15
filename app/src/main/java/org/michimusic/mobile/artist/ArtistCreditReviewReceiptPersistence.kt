package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.data.artist.ArtistCreditReviewReceiptRecord
import org.michimusic.data.repository.MusicRootPreferences

/** Local Room persistence for explicit-review proof; it never updates identity or bindings. */
internal class ArtistCreditReviewReceiptPersistence(private val context: Context) {
    private val app = context.applicationContext
    private fun sourceId(): String = MusicRootPreferences(app).currentRoot().let { root ->
        if (root.isDefault) "device_music_root" else root.sourceId.orEmpty()
    }

    /** The caller must capture the root BEFORE its network request. No UI disk IO. */
    suspend fun scopeFor(
        libraryId: String?, credit: String, albumKey: String?, trackId: String?,
    ): ArtistCreditReviewScope? = withContext(Dispatchers.IO) {
        if (libraryId.isNullOrBlank() || albumKey.isNullOrBlank() || trackId.isNullOrBlank())
            return@withContext null
        ArtistCreditReviewScope(libraryId, sourceId(),
            credit.trim().lowercase(java.util.Locale.ROOT), albumKey, trackId)
    }

    suspend fun save(
        scope: ArtistCreditReviewScope,
        claim: MusicBrainzCreditClaim,
        proof: MusicBrainzCreditProofResult,
    ): Boolean = withContext(Dispatchers.IO) {
        if (sourceId() != scope.sourceId) return@withContext false
        val receipt = ArtistCreditReviewReceiptPolicy.create(scope, claim, proof, System.currentTimeMillis())
            ?: return@withContext false
        val db = ArtistEnrichmentPersistence.database(app)
        val dao = db.artistEnrichmentDao()
        db.withTransaction {
            if (sourceId() != scope.sourceId) return@withTransaction false
            dao.pruneExpiredCreditReviewReceipts(scope.libraryId,
                receipt.verifiedAtMs - ArtistCreditReviewReceiptPolicy.TTL_MS)
            dao.putCreditReviewReceipt(receipt.toRecord())
            true
        }
    }

    suspend fun load(
        scope: ArtistCreditReviewScope,
        claim: MusicBrainzCreditClaim,
    ): Boolean = withContext(Dispatchers.IO) {
        if (sourceId() != scope.sourceId) return@withContext false
        val record = ArtistEnrichmentPersistence.database(app).artistEnrichmentDao()
            .getCreditReviewReceipt(scope.libraryId, scope.sourceId, scope.creditKey,
                scope.albumKey, scope.trackId) ?: return@withContext false
        val candidate = record.toReceipt() ?: return@withContext false
        sourceId() == scope.sourceId &&
            ArtistCreditReviewReceiptPolicy.isCurrent(candidate, scope, claim, System.currentTimeMillis())
    }

    private fun ArtistCreditReviewReceipt.toRecord(): ArtistCreditReviewReceiptRecord =
        ArtistCreditReviewReceiptRecord(scope.libraryId, scope.sourceId, scope.creditKey,
            scope.albumKey, scope.trackId, claim.artistMbid, requireNotNull(claim.recordingMbid),
            requireNotNull(claim.releaseMbid), claim.role.name, verifiedAtMs)

    private fun ArtistCreditReviewReceiptRecord.toReceipt(): ArtistCreditReviewReceipt? {
        val roleValue = VerifiedCreditRole.entries.firstOrNull { it.name == role } ?: return null
        return ArtistCreditReviewReceipt(
            ArtistCreditReviewScope(libraryId, sourceId, creditKey, albumKey, trackId),
            MusicBrainzCreditClaim(artistMbid, recordingMbid, releaseMbid, roleValue), verifiedAtMs)
    }
}
