package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.cache.EmbeddedTagEvidence

class RecoveryFingerprintPolicyTest {
    private val stamp = RecoveryDocumentStamp(size = 2048, modifiedAt = 42)
    private val persisted = EmbeddedTagEvidence(
        sourceId = "saf-a", trackId = "track-1", sizeBytes = 2048,
        modifiedAt = 42, parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
    )
    private fun check(
        before: RecoveryDocumentStamp? = stamp,
        after: RecoveryDocumentStamp? = stamp,
        indexed: EmbeddedTagEvidence? = persisted,
        isMediaStore: Boolean = false,
        owner: String = "saf-a",
        size: Long = 2048,
    ): Boolean = RecoveryFingerprintPolicy.isCurrent(
        owner, "track-1", size, before, after, indexed, isMediaStore,
    )

    @Test fun unchangedSafFingerprintMatchesItsOriginalOwner() {
        assertTrue(check())
    }

    @Test fun sameSizeReplacementWithNewMtimeIsRejected() {
        assertFalse(check(after = stamp.copy(modifiedAt = 43)))
        assertFalse(check(before = stamp.copy(modifiedAt = 43),
            after = stamp.copy(modifiedAt = 43)))
    }

    @Test fun unreliableOrMissingProviderFingerprintFailsClosed() {
        assertFalse(check(before = null))
        assertFalse(check(after = null))
        assertFalse(check(before = stamp.copy(modifiedAt = 0),
            after = stamp.copy(modifiedAt = 0)))
        assertFalse(check(before = stamp.copy(size = 0), after = stamp.copy(size = 0)))
    }

    @Test fun ownerVersionAndKnownSizeMustMatchSafSidecar() {
        assertFalse(check(owner = "saf-b"))
        assertFalse(check(size = 2047))
        assertFalse(check(indexed = persisted.copy(trackId = "other")))
        assertFalse(check(indexed = persisted.copy(parserVersion = 2)))
        assertFalse(check(indexed = null))
    }

    @Test fun mediaStoreRequiresFreshBeforeAfterButNotSafSidecar() {
        assertTrue(check(isMediaStore = true, indexed = null))
        assertFalse(check(isMediaStore = true, indexed = null,
            after = stamp.copy(size = 2049)))
    }
}
