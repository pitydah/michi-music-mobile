package org.michimusic.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.transfer.TransferCheckpoint
import org.michimusic.link.transfer.TransferState

class TransferResumeBoundaryPolicyTest {
    private val hash = "a".repeat(64)
    private fun cp(
        state: TransferState = TransferState.DOWNLOADING,
        etag: String = "\"revision-1\"",
        verified: Long = 40L,
        sha: String = hash,
        part: String = "/tmp/test.part",
        final: String = "/tmp/test.flac",
        size: Long = 100L,
    ) = TransferCheckpoint(
        trackId = "song", tempPath = part, finalPath = final,
        bytesVerified = verified, expectedSize = size, sha256Expected = sha,
        etag = etag, state = state,
    )
    private fun allowed(checkpoint: TransferCheckpoint? = cp(), bytes: Long = 40L,
                        size: Long = 100L, sha: String = hash): Boolean =
        TransferResumeBoundaryPolicy.mayAppend(
            checkpoint, "/tmp/test.part", "/tmp/test.flac", size, sha, bytes)

    @Test fun permitsMatchingVerifiedRevision() = assertTrue(allowed())
    @Test fun permitsPreservedUnverifiedSuffixOnlyFromVerifiedOffset() = assertTrue(allowed(bytes = 65))
    @Test fun pausedMayResume() = assertTrue(allowed(cp(state = TransferState.PAUSED)))
    @Test fun failedMayResumeOnlyWithCompleteEvidence() = assertTrue(allowed(cp(state = TransferState.FAILED)))
    @Test fun noCheckpointRejectsExistingPart() = assertFalse(allowed(null))
    @Test fun noVerifiedBytesRejects() = assertFalse(allowed(cp(verified = 0)))
    @Test fun truncatedPartRejects() = assertFalse(allowed(bytes = 39))
    @Test fun entireFileIsNotAPartial() = assertFalse(allowed(bytes = 100))
    @Test fun verifiedOffsetAtEndIsNotPartial() = assertFalse(allowed(cp(verified = 100)))
    @Test fun sizeMismatchRejects() = assertFalse(allowed(cp(size = 99)))
    @Test fun digestMismatchRejects() = assertFalse(allowed(cp(sha = "b".repeat(64))))
    @Test fun malformedDigestRejects() = assertFalse(allowed(sha = "not-sha"))
    @Test fun mismatchedTempPathRejects() = assertFalse(allowed(cp(part = "/tmp/other.part")))
    @Test fun mismatchedFinalPathRejects() = assertFalse(allowed(cp(final = "/tmp/other.flac")))
    @Test fun pendingStateDoesNotProveBytes() = assertFalse(allowed(cp(state = TransferState.QUEUED)))
    @Test fun completedStateCannotAppend() = assertFalse(allowed(cp(state = TransferState.COMPLETED)))
    @Test fun committingStateCannotAppend() = assertFalse(allowed(cp(state = TransferState.COMMITTING)))
    @Test fun weakEtagRejects() = assertFalse(allowed(cp(etag = "W/\"revision-1\"")))
    @Test fun bareEtagRejects() = assertFalse(allowed(cp(etag = "revision-1")))
    @Test fun emptyEtagRejects() = assertFalse(allowed(cp(etag = "")))
    @Test fun controlCharEtagRejects() = assertFalse(allowed(cp(etag = "\"abc\nxyz\"")))
    @Test fun nestedQuoteRejects() = assertFalse(allowed(cp(etag = "\"a\"b\"")))
    @Test fun extremelyLongEtagRejects() = assertFalse(allowed(cp(etag = "\"" + "a".repeat(600) + "\"")))
    @Test fun zeroExpectedSizeRejects() = assertFalse(allowed(cp(size = 0), size = 0))

    // --- A69-02: the legacy /download protocol publishes no revision validator ---

    private fun legacyAllowed(
        checkpoint: TransferCheckpoint? = cp(etag = ""),
        bytes: Long = 40L,
        size: Long = 100L,
        sha: String = hash,
        part: String = "/tmp/test.part",
        final: String = "/tmp/test.flac",
    ): Boolean = TransferResumeBoundaryPolicy.mayAppendWithoutValidator(
        checkpoint, part, final, size, sha, bytes)

    @Test fun legacyCaseBResumesWithoutAnyRevisionValidator() = assertTrue(legacyAllowed())

    @Test fun legacyCaseBIgnoresAWeakOrEmptyValidator() {
        // The asymmetry is the point: AirCat refuses these, the legacy path has
        // nothing to compare an If-Range against and must not pretend otherwise.
        assertFalse(allowed(cp(etag = "")))
        assertTrue(legacyAllowed(cp(etag = "")))
        assertFalse(allowed(cp(etag = "W/\"revision-1\"")))
        assertTrue(legacyAllowed(cp(etag = "W/\"revision-1\"")))
    }

    @Test fun legacyCaseBPausedAndFailedStillResume() {
        assertTrue(legacyAllowed(cp(etag = "", state = TransferState.PAUSED)))
        assertTrue(legacyAllowed(cp(etag = "", state = TransferState.FAILED)))
    }

    @Test fun legacyCaseCNoDigestNeverAppends() {
        // Size is a length, not an identity: with no digest the caller must ask
        // for the file from zero instead of gluing unauthenticated bytes.
        assertFalse(legacyAllowed(cp(etag = "", sha = ""), sha = ""))
    }

    @Test fun legacyCaseCMalformedDigestNeverAppends() =
        assertFalse(legacyAllowed(cp(etag = "", sha = "not-sha"), sha = "not-sha"))

    @Test fun legacyAppendRequiresTheSameDestinationPart() =
        assertFalse(legacyAllowed(cp(etag = "", part = "/tmp/other.part")))

    @Test fun legacyAppendRequiresTheSameFinalPath() =
        assertFalse(legacyAllowed(cp(etag = "", final = "/tmp/other.flac")))

    @Test fun legacyAppendRequiresTheSameDeclaredSize() =
        assertFalse(legacyAllowed(cp(etag = "", size = 99)))

    @Test fun legacyAppendRequiresTheSameDigest() =
        assertFalse(legacyAllowed(cp(etag = "", sha = "b".repeat(64))))

    @Test fun legacyAppendRejectsAPartOutsideTheBoundaries() {
        assertFalse(legacyAllowed(bytes = 0))
        assertFalse(legacyAllowed(bytes = 100))
        assertFalse(legacyAllowed(bytes = 101))
    }

    @Test fun legacyAppendRejectsStatesThatProveNothing() {
        assertFalse(legacyAllowed(cp(etag = "", state = TransferState.QUEUED)))
        assertFalse(legacyAllowed(cp(etag = "", state = TransferState.COMMITTING)))
        assertFalse(legacyAllowed(cp(etag = "", state = TransferState.COMPLETED)))
    }

    @Test fun legacyAppendRequiresAPersistedCheckpoint() = assertFalse(legacyAllowed(null))

    @Test fun legacyAppendRejectsBlankPaths() {
        assertFalse(legacyAllowed(part = ""))
        assertFalse(legacyAllowed(final = ""))
    }

    @Test fun legacyAppendRejectsAZeroDeclaredSize() =
        assertFalse(legacyAllowed(cp(etag = "", size = 0), size = 0))
}
