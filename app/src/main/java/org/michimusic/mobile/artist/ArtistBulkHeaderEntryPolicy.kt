package org.michimusic.mobile.artist

/** D32: header tap grants permission for a LOCAL preview only, never for HTTP or a bulk job. */
internal enum class ArtistBulkHeaderEntry {
    IGNORE,
    READ_LOCAL,
    SELECT_ONE_LIBRARY,
    INVALID_MEMBERSHIP,
}

/** An explicit tap is necessary; no composition or scope change may start a read. */
internal fun decideArtistBulkHeaderEntry(
    openRequestId: Int,
    libraryId: String?,
    hasExactLibraryAuthority: Boolean,
    membershipsReady: Boolean,
): ArtistBulkHeaderEntry = when {
    openRequestId <= 0 -> ArtistBulkHeaderEntry.IGNORE
    libraryId.isNullOrBlank() || !hasExactLibraryAuthority -> ArtistBulkHeaderEntry.SELECT_ONE_LIBRARY
    !membershipsReady -> ArtistBulkHeaderEntry.INVALID_MEMBERSHIP
    else -> ArtistBulkHeaderEntry.READ_LOCAL
}
