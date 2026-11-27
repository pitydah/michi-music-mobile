package org.michimusic.data.metadata

import java.io.File
import org.michimusic.data.cache.TrackDao

/**
 * Conservative terminal-marker retention. A marker is deleted only when it is
 * older than the retention window and no pending journal or reconciliation
 * receipt can still replay its transaction. Any ambiguous durable state fails
 * closed: inspection is skipped and no marker is removed.
 */
internal class MetadataAppliedMarkerRetention(
    private val journal: MetadataRecoveryJournalV1,
    private val dao: TrackDao,
    private val receiptRoot: File,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
) {
    suspend fun prune(now: Long = System.currentTimeMillis()): Int {
        val inspection = journal.inspect()
        if (inspection is MetadataRecoveryJournalV1.Inspection.Blocked) return 0
        val protectedIds = linkedSetOf<String>()
        if (inspection is MetadataRecoveryJournalV1.Inspection.Pending)
            protectedIds += inspection.entry.id
        for (file in MetadataIndexReconciliationReceipt.list(receiptRoot)) {
            val receipt = MetadataIndexReconciliationReceipt.read(file) ?: return 0
            protectedIds += receipt.transactionId
        }
        val cutoff = now - retentionMillis
        return if (protectedIds.isEmpty()) {
            dao.pruneAppliedTransactionsBefore(cutoff)
        } else {
            dao.pruneAppliedTransactionsBeforeExcept(cutoff, protectedIds.toList())
        }
    }

    private companion object {
        const val DEFAULT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
