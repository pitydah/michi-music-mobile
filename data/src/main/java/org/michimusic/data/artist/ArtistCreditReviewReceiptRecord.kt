package org.michimusic.data.artist

import androidx.room.Entity

/** A locally retained human-review receipt. NOT a confirmed artist or an album binding. */
@Entity(
    tableName = "artist_credit_review_receipt",
    primaryKeys = ["libraryId", "sourceId", "creditKey", "albumKey", "trackId"],
)
data class ArtistCreditReviewReceiptRecord(
    val libraryId: String,
    val sourceId: String,
    val creditKey: String,
    val albumKey: String,
    val trackId: String,
    val artistMbid: String,
    val recordingMbid: String,
    val releaseMbid: String,
    val role: String,
    val verifiedAtMs: Long,
)
