package org.michimusic.core.metadata

import org.junit.Assert.assertThrows
import org.junit.Test

class MetadataCommittedLibraryChangeTest {
    private val target = MetadataTarget(
        sourceId = "lib_device_local",
        trackId = "local_7",
        uri = "content://media/external/audio/media/7",
        evidenceToken = "cold-read",
    )

    @Test fun committedRevisionMustAdvance() {
        assertThrows(IllegalArgumentException::class.java) {
            MetadataCommittedLibraryChange(
                transactionId = "tx",
                sourceId = target.sourceId,
                trackId = target.trackId,
                uri = target.uri,
                changedFields = setOf(MetadataField.TITLE),
                resourceOperations = emptySet(),
                snapshot = MetadataSnapshot(
                    target = target,
                    tagsVerified = true,
                    known = mapOf(MetadataField.TITLE to listOf("After")),
                ),
                previousRevision = 4,
                committedRevision = 4,
                committedBytes = 100,
                committedSha256 = "a".repeat(64),
            )
        }
    }
}
