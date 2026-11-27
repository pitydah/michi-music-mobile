package org.michimusic.mobile.artist

/**
 * D21 S4-C1. An in-memory, non-executable selection proposal. This does not
 * schedule WorkManager, download content or authorize an individual Room write.
 * The later executor MUST obtain a fresh permission and revalidate each
 * association and root/catalog generation inside its commit boundary.
 */
enum class ArtistBulkDraftRejection {
    INVALID_PREVIEW,
    NO_SELECTION,
    TOO_MANY_ARTISTS,
    UNKNOWN_OR_DUPLICATE_SELECTION,
    UNVERIFIED_ARTIST,
    EXPLICIT_ACKNOWLEDGMENT_REQUIRED,
    INVALID_RECEIPT_ID,
    INVALID_TIMESTAMP,
}

enum class ArtistBulkPlannedStatus {
    PREPARED_NOT_SUBMITTED,
    CANCELLED_NOT_SUBMITTED,
}

data class ArtistBulkPlannedReceipt(
    val fingerprint: String,
    val libraryId: String,
    val artistCredit: String,
    val albumIds: List<String>,
    val mbid: String,
    val associationId: String,
    val status: ArtistBulkPlannedStatus = ArtistBulkPlannedStatus.PREPARED_NOT_SUBMITTED,
)

data class ArtistBulkPreparedSession(
    val sessionId: String,
    val snapshot: ArtistBulkReadSnapshot,
    val createdAtEpochMs: Long,
    val receipts: List<ArtistBulkPlannedReceipt>,
) {
    /** A prepared draft is NEVER an executable or durable authorization. */
    val mayExecute: Boolean get() = false
    val cancelled: Boolean get() = receipts.isNotEmpty() &&
        receipts.all { it.status == ArtistBulkPlannedStatus.CANCELLED_NOT_SUBMITTED }

    fun cancel(): ArtistBulkPreparedSession = copy(
        receipts = receipts.map { it.copy(status = ArtistBulkPlannedStatus.CANCELLED_NOT_SUBMITTED) },
    )
}

sealed interface ArtistBulkDraftResult {
    data class Prepared(val session: ArtistBulkPreparedSession) : ArtistBulkDraftResult
    data class Rejected(val reason: ArtistBulkDraftRejection) : ArtistBulkDraftResult
}

/** A fingerprint is only a UI selection key; it is NOT a write authorization. */
fun artistBulkSelectableFingerprint(row: ArtistBulkPreviewRow): String? {
    if (row.disposition != ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW) return null
    if (row.credit.isBlank() || row.trackCount <= 0 || row.albumIds.isEmpty() ||
        row.albumIds.any(String::isBlank) || row.albumIds.distinct().size != row.albumIds.size ||
        row.mbid.isNullOrBlank() || row.associationId.isNullOrBlank()
    ) return null
    val fingerprint = row.diagnosticFingerprint ?: return null
    if (fingerprint.length != 64 || fingerprint.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return fingerprint
}

/**
 * Deterministic validation, no side effects. The caller must first reread the
 * current root/library/catalog witness and compare it to preview.snapshot.
 * No implicit selection: every selected fingerprint comes from a user checkbox.
 */
fun prepareArtistBulkSession(
    preview: ArtistBulkPreview,
    selectedFingerprints: Set<String>,
    acknowledgedPreparationOnly: Boolean,
    receiptId: String,
    createdAtEpochMs: Long,
    maxArtists: Int = 64,
): ArtistBulkDraftResult {
    require(maxArtists in 1..128)
    if (!preview.snapshot.isValid() || preview.blocked != null) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.INVALID_PREVIEW)
    }
    if (selectedFingerprints.isEmpty()) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.NO_SELECTION)
    }
    if (selectedFingerprints.size > maxArtists) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.TOO_MANY_ARTISTS)
    }
    if (!acknowledgedPreparationOnly) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.EXPLICIT_ACKNOWLEDGMENT_REQUIRED)
    }
    if (receiptId.isBlank() || receiptId.length > 128 || receiptId.any(Char::isWhitespace)) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.INVALID_RECEIPT_ID)
    }
    if (createdAtEpochMs <= 0L) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.INVALID_TIMESTAMP)
    }
    // Reject ambiguous fingerprints even if only one of the colliding rows was checked.
    val allKeys = preview.rows.mapNotNull(::artistBulkSelectableFingerprint)
    if (allKeys.size != allKeys.toSet().size) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.UNKNOWN_OR_DUPLICATE_SELECTION)
    }
    val selectable = preview.rows.filter { artistBulkSelectableFingerprint(it) != null }
    val keyed = selectable.associateBy { artistBulkSelectableFingerprint(it)!! }
    if (selectedFingerprints.any { it !in keyed }) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.UNKNOWN_OR_DUPLICATE_SELECTION)
    }
    val chosen = preview.rows.filter { artistBulkSelectableFingerprint(it) in selectedFingerprints }
    if (chosen.size != selectedFingerprints.size) {
        return ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.UNVERIFIED_ARTIST)
    }
    val receipts = chosen.map { row ->
        ArtistBulkPlannedReceipt(
            fingerprint = artistBulkSelectableFingerprint(row)!!,
            libraryId = preview.snapshot.libraryId,
            artistCredit = row.credit,
            albumIds = row.albumIds.toList(),
            mbid = row.mbid!!,
            associationId = row.associationId!!,
        )
    }
    return ArtistBulkDraftResult.Prepared(
        ArtistBulkPreparedSession(receiptId, preview.snapshot, createdAtEpochMs, receipts),
    )
}

/** A historical/cancelled draft cannot occupy the active slot of a fresh preview. */
internal fun artistBulkCanRestoreDraft(
    draft: ArtistBulkPreparedSession,
    preview: ArtistBulkPreview,
    current: ArtistBulkReadSnapshot,
): Boolean {
    if (draft.cancelled || draft.mayExecute || draft.receipts.size !in 1..64 ||
        preview.blocked != null || !current.isValid() ||
        draft.snapshot != preview.snapshot || current != preview.snapshot ||
        draft.receipts.map { it.fingerprint }.distinct().size != draft.receipts.size) return false
    // Fingerprints intentionally do not contain MBID/association. Compare those
    // independently: Room identity may change without changing the library hash.
    val candidates = preview.rows.groupBy(::artistBulkSelectableFingerprint)
    return draft.receipts.all { receipt ->
        val row = candidates[receipt.fingerprint]?.singleOrNull() ?: return@all false
        receipt.status == ArtistBulkPlannedStatus.PREPARED_NOT_SUBMITTED &&
            receipt.libraryId == current.libraryId && receipt.artistCredit == row.credit &&
            receipt.albumIds.sorted() == row.albumIds.sorted() && receipt.mbid == row.mbid &&
            receipt.associationId == row.associationId
    }
}
