package org.michimusic.mobile.artist

/** UI-only classification. Neither a filter nor a displayed count is execution consent. */
enum class ArtistBulkReviewFilter(val title: String) {
    ALL("Todos"), READY("Verificados"), REVIEW("Pendientes"), BLOCKED("Conflictos"),
}

fun artistBulkReviewFilterMatches(
    row: ArtistBulkPreviewRow,
    filter: ArtistBulkReviewFilter,
): Boolean = when (filter) {
    ArtistBulkReviewFilter.ALL -> true
    ArtistBulkReviewFilter.READY -> row.disposition == ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW
    ArtistBulkReviewFilter.REVIEW -> row.disposition in setOf(
        ArtistBulkDisposition.IDENTITY_REQUIRED,
        ArtistBulkDisposition.MEMBERSHIP_RECONFIRMATION,
        ArtistBulkDisposition.PROFILE_MISSING,
    )
    ArtistBulkReviewFilter.BLOCKED -> row.disposition in setOf(
        ArtistBulkDisposition.IDENTITY_CONFLICT,
        ArtistBulkDisposition.INVALID_SCOPE,
        ArtistBulkDisposition.DUPLICATE_SCOPE,
        ArtistBulkDisposition.READ_ERROR,
    )
}

fun artistBulkReviewFilterCount(
    rows: List<ArtistBulkPreviewRow>,
    filter: ArtistBulkReviewFilter,
): Int = rows.count { artistBulkReviewFilterMatches(it, filter) }

fun artistBulkFilteredRows(
    rows: List<ArtistBulkPreviewRow>,
    filter: ArtistBulkReviewFilter,
): List<ArtistBulkPreviewRow> = rows.filter { artistBulkReviewFilterMatches(it, filter) }
