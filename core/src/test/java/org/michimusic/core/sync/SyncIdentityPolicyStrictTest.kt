package org.michimusic.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.LocalSyncTrackEvidence
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncIdentityVerdict

class SyncIdentityPolicyStrictTest {
    private val digest = "a".repeat(64)
    private val manifest = ManifestTrack(trackId = "song", size = 2048L, checksum = digest)

    private fun evidence(algorithm: String = "sha256") = LocalSyncTrackEvidence(
        sourceId = "host-a", trackId = "song", sizeBytes = 2048L,
        checksum = digest, checksumVerified = true, checksumAlgorithm = algorithm,
        managedCopyVerified = true, physicallyVerified = true, complete = true,
    )

    @Test fun explicitSha256WithPhysicalEvidenceCanSkip() {
        val verdict = SyncIdentityPolicy.classify("host-a", manifest, listOf(evidence()))
        assertEquals(SyncIdentityVerdict.HASH_VERIFIED, verdict.verdict)
        assertTrue(verdict.canSkip)
    }

    @Test fun legacyAndUnknownAlgorithmsNeverSkipEvenWithMatching64Hex() {
        for (algorithm in listOf("", "sha512", "unknown")) {
            val verdict = SyncIdentityPolicy.classify("host-a", manifest, listOf(evidence(algorithm)))
            assertFalse("Must not infer SHA-256 from checksum shape: $algorithm", verdict.canSkip)
        }
    }

    @Test fun unverifiedPhysicalFileAndForeignHostNeverSkip() {
        val vanished = evidence().copy(physicallyVerified = false)
        assertFalse(SyncIdentityPolicy.classify("host-a", manifest, listOf(vanished)).canSkip)
        val foreign = SyncIdentityPolicy.classify("host-b", manifest, listOf(evidence()))
        assertEquals(SyncIdentityVerdict.DIFFERENT_SOURCE, foreign.verdict)
        assertFalse(foreign.canSkip)
    }

    @Test fun equalSizeButDifferentSha256IsNotSkippable() {
        val changed = evidence().copy(checksum = "b".repeat(64))
        val verdict = SyncIdentityPolicy.classify("host-a", manifest, listOf(changed))
        assertEquals(SyncIdentityVerdict.CONTENT_MISMATCH, verdict.verdict)
        assertFalse(verdict.canSkip)
    }
}
