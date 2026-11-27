package org.michimusic.data.artist

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** D23: per-artist explicit consent for a future *profile lookup*; NEVER a job ticket.
 * This record cannot authorize photo or tag changes and has no dispatch method.
 */
@Entity(
    tableName = "artist_bulk_permission",
    primaryKeys = ["sessionId", "fingerprint"],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["permitId"], unique = true),
    ],
    foreignKeys = [ForeignKey(
        entity = ArtistBulkReceiptRecord::class,
        parentColumns = ["sessionId", "fingerprint"],
        childColumns = ["sessionId", "fingerprint"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ArtistBulkPermissionRecord(
    val sessionId: String,
    val fingerprint: String,
    val permitId: String,
    val libraryId: String,
    val sourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val libraryRevision: Long,
    val artistCredit: String,
    val mbid: String,
    val associationId: String,
    val allowedOperation: String = PROFILE_LOOKUP_ONLY,
    val status: String = CONSENT_STORED_NOT_DISPATCHED,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val revision: Long = 0L,
) {
    companion object {
        const val PROFILE_LOOKUP_ONLY = "PROFILE_LOOKUP_ONLY"
        const val CONSENT_STORED_NOT_DISPATCHED = "CONSENT_STORED_NOT_DISPATCHED"
        const val REVOKED_NOT_DISPATCHED = "REVOKED_NOT_DISPATCHED"
        // D24: a reservation is NOT a request, HTTP capability, or scheduled job.
        const val RESERVED_NO_IO = "RESERVED_NO_IO"
        const val RELEASED_NO_IO = "RELEASED_NO_IO"
        const val MAX_LIFETIME_MS = 5 * 60 * 1000L
    }

    /** A persistent consent receipt is not sufficient authority to run WorkManager. */
    fun isCurrent(now: Long): Boolean = status == CONSENT_STORED_NOT_DISPATCHED &&
        now >= issuedAtEpochMs && now < expiresAtEpochMs
}
