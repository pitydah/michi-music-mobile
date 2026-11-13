package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.michimusic.core.models.Track

class RecoveryPageCommitPolicyTest {
    private fun track(owner: String = "saf-a", genre: String = "") = Track(
        id = "id", title = "Song", sourceId = owner,
        filepath = "content://file/1", size = 100, genre = genre)

    @Test fun confirmedSafPagePublishesOnlyCommittedOwner() {
        val before = listOf(track("saf-a"), track("saf-b"))
        val after = RecoveryPageCommitPolicy.apply(before,
            mapOf(0 to before[0].copy(genre = "Pop rock"),
                1 to before[1].copy(genre = "Jazz")),
            setOf("saf-a" to "id"), "saf-a", false)
        assertEquals("Pop rock", after[0].genre)
        assertEquals("", after[1].genre)
    }
    @Test fun rejectedTransactionNeverUpdatesCache() {
        val before = listOf(track())
        val after = RecoveryPageCommitPolicy.apply(before,
            mapOf(0 to before[0].copy(genre = "Metal")), emptySet(), "saf-a", false)
        assertEquals("", after.single().genre)
    }
    @Test fun sameIdDifferentPathOrSizeCanNeverBorrowReceipt() {
        val before = listOf(track())
        for (changed in listOf(before[0].copy(filepath = "content://other"),
            before[0].copy(size = 101))) {
            val after = RecoveryPageCommitPolicy.apply(before,
                mapOf(0 to changed.copy(genre = "Rock")), setOf("saf-a" to "id"),
                "saf-a", false)
            assertEquals("", after.single().genre)
        }
    }
    @Test fun defaultRootMayConfirmAValidManualRecovery() {
        val before = listOf(track(owner = ""))
        val after = RecoveryPageCommitPolicy.apply(before,
            mapOf(0 to before[0].copy(genre = "Jazz; Fusion")), emptySet(), "", true)
        assertEquals("Jazz; Fusion", after.single().genre)
    }
    @Test fun confirmedFirstPageSurvivesCancellationAndRetryOfNextPage() {
        val initial = listOf(
            Track(id = "a", title = "A", sourceId = "saf-a",
                filepath = "content://a", size = 90, genre = ""),
            Track(id = "b", title = "B", sourceId = "saf-a",
                filepath = "content://b", size = 100, genre = ""),
        )
        // The first Room receipt was confirmed before the user pressed Stop.
        val committedPageOne = RecoveryPageCommitPolicy.apply(initial,
            mapOf(0 to initial[0].copy(genre = "Jazz")),
            setOf("saf-a" to "a"), "saf-a", false)
        assertEquals("Jazz", committedPageOne[0].genre)
        assertEquals("", committedPageOne[1].genre)
        // The interrupted second page did not receive a Room receipt.
        val interrupted = RecoveryPageCommitPolicy.apply(committedPageOne,
            mapOf(1 to committedPageOne[1].copy(genre = "Rock")),
            emptySet(), "saf-a", false)
        assertEquals("Jazz", interrupted[0].genre)
        assertEquals("", interrupted[1].genre)
        // A new explicit user action confirms only the remaining genre.
        val resumed = RecoveryPageCommitPolicy.apply(interrupted,
            mapOf(1 to interrupted[1].copy(genre = "Rock")),
            setOf("saf-a" to "b"), "saf-a", false)
        assertEquals(listOf("Jazz", "Rock"), resumed.map { it.genre })
    }

    @Test fun emptyPageLeavesSnapshotIdentityUntouched() {
        val before = listOf(track())
        assertSame(before, RecoveryPageCommitPolicy.apply(before, emptyMap(), emptySet(), "saf-a", false))
    }
}
