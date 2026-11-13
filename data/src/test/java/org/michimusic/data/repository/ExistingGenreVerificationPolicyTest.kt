package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.models.Track

class ExistingGenreVerificationPolicyTest {
    @Test fun comparesLiteralTagsWithoutInventingSplitMemberships() {
        assertEquals(ExistingGenreComparison.MATCH,
            ExistingGenreVerificationPolicy.compare("Jazz; Fusion", EmbeddedTagFields(genres = listOf("Jazz; Fusion"))))
        assertEquals(ExistingGenreComparison.DIFFERENT,
            ExistingGenreVerificationPolicy.compare("Jazz", EmbeddedTagFields(genres = listOf("Jazz", "Fusion"))))
        assertEquals(ExistingGenreComparison.MATCH,
            ExistingGenreVerificationPolicy.compare("pop rock", EmbeddedTagFields(genres = listOf("Pop Rock"))))
    }

    @Test fun unavailableAndBlankEmbeddedTagsAreNotInvented() {
        assertEquals(ExistingGenreComparison.READ_FAILED,
            ExistingGenreVerificationPolicy.compare("Rock", null))
        assertEquals(ExistingGenreComparison.NO_EMBEDDED_GENRE,
            ExistingGenreVerificationPolicy.compare("Rock", EmbeddedTagFields()))
    }

    @Test fun aProviderGenreNotMatchingRealTconIsReportedButNotRewritten() {
        val provider = "Dance"
        assertEquals(ExistingGenreComparison.DIFFERENT,
            ExistingGenreVerificationPolicy.compare(provider, EmbeddedTagFields(genres = listOf("Pop rock"))))
        assertEquals("Dance", provider)
    }

    @Test fun invalidOrStaleContinuationNeverReportsAnEmptySuccessfulPage() {
        val policy = ExistingGenreVerificationPolicy
        assertEquals(0, policy.continuationStart(0, 256, "snapshot-a", null))
        assertEquals(0, policy.continuationStart(0, 0, "snapshot-empty", null))
        assertEquals(128, policy.continuationStart(128, 256, "snapshot-a", "snapshot-a"))
        assertEquals(null, policy.continuationStart(-1, 256, "snapshot-a", null))
        assertEquals(null, policy.continuationStart(256, 256, "snapshot-a", "snapshot-a"))
        assertEquals(null, policy.continuationStart(257, 256, "snapshot-a", "snapshot-a"))
        assertEquals(null, policy.continuationStart(128, 256, "snapshot-a", "snapshot-b"))
        assertEquals(null, policy.continuationStart(128, 256, "snapshot-a", null))
    }

    @Test fun snapshotIsDeterministicAndInvalidatesOnOwnerOrderAndGenreChange() {
        val a = Track(id = "a", title = "A", genre = "Pop rock", sourceId = "owner", filepath = "content://a", size = 12L)
        val b = Track(id = "b", title = "B", genre = "Jazz", sourceId = "owner", filepath = "content://b", size = 10L)
        val token = ExistingGenreVerificationPolicy.snapshotToken("owner", listOf(a,b))
        assertEquals(64, token.length)
        assertEquals(token, ExistingGenreVerificationPolicy.snapshotToken("owner", listOf(a,b)))
        org.junit.Assert.assertNotEquals(token, ExistingGenreVerificationPolicy.snapshotToken("other", listOf(a,b)))
        org.junit.Assert.assertNotEquals(token, ExistingGenreVerificationPolicy.snapshotToken("owner", listOf(b,a)))
        org.junit.Assert.assertNotEquals(token, ExistingGenreVerificationPolicy.snapshotToken("owner", listOf(a,b.copy(genre="Rock"))))
        org.junit.Assert.assertNotEquals(token, ExistingGenreVerificationPolicy.snapshotToken("owner", listOf(a,b.copy(size=11L))))
    }

}
