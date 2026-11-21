package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataPrivateTrialPolicyTest {
    private fun decision(state: MetadataPrivateTrialPolicy.State,
        clicked: Boolean = true, confirmed: Boolean = true, current: Boolean = true,
        backup: Boolean = true) = MetadataPrivateTrialPolicy.mayRestorePrivateTrial(
        state, clicked, confirmed, current, backup)

    @Test fun cannotRestoreWithoutExplicitUserGesture() {
        assertEquals(MetadataPrivateTrialPolicy.Reason.NO_EXPLICIT_ACTION,
            decision(MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN,clicked=false).reason)
    }
    @Test fun secondConfirmationIsMandatory() {
        assertEquals(MetadataPrivateTrialPolicy.Reason.NO_SECOND_CONFIRMATION,
            decision(MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN,confirmed=false).reason)
    }
    @Test fun wrongTargetRejected() {
        assertEquals(MetadataPrivateTrialPolicy.Reason.WRONG_TARGET,
            decision(MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN,current=false).reason)
    }
    @Test fun damagedOriginalBackupRejected() {
        assertEquals(MetadataPrivateTrialPolicy.Reason.BACKUP_NOT_INTACT,
            decision(MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN,backup=false).reason)
    }
    @Test fun privateCandidateMayBeRestoredOnlyWithinSandbox() {
        assertEquals(MetadataPrivateTrialPolicy.Restoration.READY_FOR_PRIVATE_RESTORE,
            decision(MetadataPrivateTrialPolicy.State.CANDIDATE_PRIVATE_INTACT).restoration)
    }
    @Test fun interruptedPrivateFileMayBeRestoredOnlyWithinSandbox() {
        assertEquals(MetadataPrivateTrialPolicy.Restoration.READY_FOR_PRIVATE_RESTORE,
            decision(MetadataPrivateTrialPolicy.State.PRIVATE_BYTES_UNKNOWN).restoration)
    }
    @Test fun alreadyRestoredPrivateCopyIsNoop() {
        assertEquals(MetadataPrivateTrialPolicy.Restoration.ALREADY_PRIVATE_ORIGINAL,
            decision(MetadataPrivateTrialPolicy.State.ORIGINAL_PRIVATE_INTACT).restoration)
    }
    @Test fun noTrialAndUnreadableAreNotRestorable() {
        for (state in listOf(MetadataPrivateTrialPolicy.State.NOT_STARTED,
            MetadataPrivateTrialPolicy.State.PRIVATE_UNREADABLE,
            MetadataPrivateTrialPolicy.State.BACKUP_INVALID)) {
            assertEquals(MetadataPrivateTrialPolicy.Restoration.BLOCKED,decision(state).restoration)
        }
    }
    @Test fun successfulPrivateExerciseNeverCertifiesSourceWriter() {
        for (state in MetadataPrivateTrialPolicy.State.entries) {
            assertFalse(MetadataPrivateTrialPolicy.providerCommitCertifiedByPrivateTrial(state))
        }
    }
}
