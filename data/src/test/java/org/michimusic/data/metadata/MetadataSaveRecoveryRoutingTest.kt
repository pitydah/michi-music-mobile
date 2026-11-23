package org.michimusic.data.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataRecoveryPhase

class MetadataSaveRecoveryRoutingTest {
    private fun discard(
        phase: MetadataRecoveryPhase,
        source: String = "lib_device_local",
        track: String = "42",
        uri: String = "content://media/external/audio/media/42",
    ) = MetadataSaveRecoveryRouting.mayDiscardPrivatePreparation(
        phase,
        source, track, uri,
        "lib_device_local", "42", "content://media/external/audio/media/42",
    )

    @Test fun pendingPostMutationMustSkipFreshSavePreflight() {
        assertTrue(MetadataSaveRecoveryRouting.requiresReadOnlyFinalization(
            MetadataRecoveryPhase.MUTATING))
        assertTrue(MetadataSaveRecoveryRouting.requiresReadOnlyFinalization(
            MetadataRecoveryPhase.MUTATION_UNKNOWN))
        assertTrue(MetadataSaveRecoveryRouting.requiresReadOnlyFinalization(
            MetadataRecoveryPhase.POSTCHECK_VERIFIED))
        assertFalse(MetadataSaveRecoveryRouting.requiresReadOnlyFinalization(
            MetadataRecoveryPhase.STAGE_READY))
        assertFalse(MetadataSaveRecoveryRouting.requiresReadOnlyFinalization(
            MetadataRecoveryPhase.ROLLBACK_PENDING))
    }

    @Test fun aDifferentSongCannotDiscardAnyPrivatePreparation() {
        assertFalse(discard(MetadataRecoveryPhase.SNAPSHOTTING, track = "other"))
        assertFalse(discard(MetadataRecoveryPhase.BACKUP_DURABLE, uri = "content://media/other"))
        assertFalse(discard(MetadataRecoveryPhase.BACKUP_DURABLE, source = "other"))
    }

    @Test fun onlySameTrackPrivatePhasesCanBeDiscarded() {
        assertTrue(discard(MetadataRecoveryPhase.SNAPSHOTTING))
        assertTrue(discard(MetadataRecoveryPhase.BACKUP_DURABLE))
        assertFalse(discard(MetadataRecoveryPhase.STAGE_READY))
        assertFalse(discard(MetadataRecoveryPhase.MUTATING))
        assertFalse(discard(MetadataRecoveryPhase.MUTATION_UNKNOWN))
        assertFalse(discard(MetadataRecoveryPhase.POSTCHECK_VERIFIED))
    }
}
