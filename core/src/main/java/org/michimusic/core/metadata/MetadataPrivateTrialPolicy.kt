package org.michimusic.core.metadata

/**
 * ME-07B: a private fault-injection exercise is a test of our recovery machinery,
 * never proof that a particular Android provider can be restored after partial writes.
 */
object MetadataPrivateTrialPolicy {
    enum class CutPoint { NONE, BEFORE_TRUNCATE, AFTER_TRUNCATE, AFTER_FIRST_CHUNK, AFTER_FSYNC }
    enum class State {
        NOT_STARTED, ORIGINAL_PRIVATE_INTACT, CANDIDATE_PRIVATE_INTACT,
        PRIVATE_BYTES_UNKNOWN, PRIVATE_UNREADABLE, BACKUP_INVALID, WRONG_TARGET,
        NOT_PREPARED, JOURNAL_UNAVAILABLE,
    }
    enum class Restoration {
        READY_FOR_PRIVATE_RESTORE, ALREADY_PRIVATE_ORIGINAL, BLOCKED,
    }
    enum class Reason {
        PRIVATE_ONLY, NO_EXPLICIT_ACTION, NO_SECOND_CONFIRMATION,
        WRONG_TARGET, NOT_PREPARED, BACKUP_NOT_INTACT, JOURNAL_UNAVAILABLE,
        NO_TRIAL, NEVER_CERTIFIES_PROVIDER,
    }
    data class Decision(val restoration: Restoration, val reason: Reason)

    /** Never issue any kind of permission for the original source. */
    fun mayRestorePrivateTrial(
        state: State, explicitUserAction: Boolean, secondConfirmation: Boolean,
        sourceStillMatched: Boolean, backupIntact: Boolean,
    ): Decision = when {
        !explicitUserAction -> Decision(Restoration.BLOCKED, Reason.NO_EXPLICIT_ACTION)
        !secondConfirmation -> Decision(Restoration.BLOCKED, Reason.NO_SECOND_CONFIRMATION)
        !sourceStillMatched -> Decision(Restoration.BLOCKED, Reason.WRONG_TARGET)
        !backupIntact -> Decision(Restoration.BLOCKED, Reason.BACKUP_NOT_INTACT)
        state == State.ORIGINAL_PRIVATE_INTACT ->
            Decision(Restoration.ALREADY_PRIVATE_ORIGINAL, Reason.PRIVATE_ONLY)
        state in setOf(State.CANDIDATE_PRIVATE_INTACT, State.PRIVATE_BYTES_UNKNOWN) ->
            Decision(Restoration.READY_FOR_PRIVATE_RESTORE, Reason.PRIVATE_ONLY)
        state == State.NOT_PREPARED -> Decision(Restoration.BLOCKED, Reason.NOT_PREPARED)
        state == State.JOURNAL_UNAVAILABLE -> Decision(Restoration.BLOCKED, Reason.JOURNAL_UNAVAILABLE)
        else -> Decision(Restoration.BLOCKED, Reason.NO_TRIAL)
    }

    /** Even perfect private trial readback cannot flip the source writer gate. */
    fun providerCommitCertifiedByPrivateTrial(state: State): Boolean = false
}
