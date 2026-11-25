package org.michimusic.data.metadata

/**
 * Reconstructs a non-authorizing continuation from durable private evidence.
 *
 * The UI may retain only [transactionId]. Every identity and digest used by the
 * continuation is recovered from the journal, intent and owner receipt.
 */
internal class MetadataPreparedSaveContinuationResolver(
    private val journal: MetadataRecoveryJournalV1,
    private val intents: MetadataCommitIntentStore,
    private val owners: MetadataRecoveryOwnerStore,
) {
    sealed interface Result {
        data class Ready(val continuation: MetadataPreparedSaveContinuation) : Result
        data class Refused(val code: String) : Result
    }

    fun resolve(
        transactionId: String,
        sourceId: String,
        trackId: String,
        uri: String,
    ): Result {
        val entry = (journal.inspect() as? MetadataRecoveryJournalV1.Inspection.Pending)
            ?.entry ?: return Result.Refused("NO_PREPARED_TRANSACTION")
        if (entry.id != transactionId || entry.sourceId != sourceId ||
            entry.trackId != trackId || entry.uri != uri
        ) return Result.Refused("PREPARED_IDENTITY_MISMATCH")
        if (entry.phase != org.michimusic.core.metadata.MetadataRecoveryPhase.STAGE_READY)
            return Result.Refused("NOT_PREPARED")
        if (!journal.privateArtifactsIntact(entry))
            return Result.Refused("PRIVATE_EVIDENCE_INVALID")
        val intent = (intents.inspect(entry) as? MetadataCommitIntentStore.Inspection.Present)
            ?.receipt ?: return Result.Refused("INTENT_NOT_DURABLE")
        val owner = owners.read(entry) ?: return Result.Refused("OWNER_RECEIPT_MISSING")
        if (owner.ownerSha256 != intent.ownerSha256 ||
            owner.sourceId != entry.sourceId || owner.trackId != entry.trackId ||
            owner.uri != entry.uri
        ) return Result.Refused("OWNER_EVIDENCE_MISMATCH")
        val continuation = MetadataPreparedSaveContinuation.from(entry, intent)
            ?: return Result.Refused("CONTINUATION_MISMATCH")
        return Result.Ready(continuation)
    }
}
