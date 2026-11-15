package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MI-23 P0 5: the preview is a LIST. Replacing one value must preserve the other
 * N-1 verified values; adding never drops existing tags; placeholders are never
 * assigned. The preview never mutates a file (writes stay fail-closed).
 */
class MichiGenrePreviewPolicyTest {
    private val policy = MichiGenrePreviewPolicy

    private fun accepted(edit: MichiGenrePreviewPolicy.Edit): MichiGenrePreviewPolicy.Preview {
        assertTrue("expected an accepted edit, got $edit", edit is MichiGenrePreviewPolicy.Edit.Accepted)
        return (edit as MichiGenrePreviewPolicy.Edit.Accepted).preview
    }

    private fun rejection(edit: MichiGenrePreviewPolicy.Edit): String {
        assertTrue("expected a rejected edit, got $edit", edit is MichiGenrePreviewPolicy.Edit.Rejected)
        return (edit as MichiGenrePreviewPolicy.Edit.Rejected).reason
    }

    @Test
    fun keepsAllVerifiedValuesByDefault() {
        val original = listOf("Post-Punk", "New Wave", "Darkwave")
        val preview = policy.of(original)
        assertEquals(original, preview.original)
        assertEquals(original, preview.proposed)
        assertFalse(preview.isModified)
        assertEquals(3, preview.diff().unchanged.size)
    }

    @Test
    fun replacingOneValuePreservesTheOthers() {
        val preview = policy.of(listOf("Post-Punk", "New Wave", "Darkwave"))
        val next = accepted(preview.replaceAt(2, "Coldwave"))
        assertEquals(listOf("Post-Punk", "New Wave", "Coldwave"), next.proposed)
        assertEquals(listOf("Post-Punk", "New Wave", "Darkwave"), next.original)
        assertEquals(listOf("Coldwave"), next.diff().added)
        assertEquals(listOf("Darkwave"), next.diff().removed)
        // Order of the untouched values is preserved.
        assertEquals("Post-Punk", next.proposed[0])
        assertEquals("New Wave", next.proposed[1])
    }

    @Test
    fun removingOneValueKeepsEveryOtherGenre() {
        val preview = policy.of(listOf("Rock", "Jazz", "Blues"))
        assertEquals(listOf("Rock", "Blues"), accepted(preview.removeAt(1)).proposed)
        assertEquals(listOf("Jazz", "Blues"), accepted(preview.removeAt(0)).proposed)
        assertEquals("INVALID_INDEX", rejection(preview.removeAt(7)))
    }

    @Test
    fun addingNeverDropsExistingTagsAndDeduplicatesByCanonicalKey() {
        val preview = policy.of(listOf("Post-Punk"))
        val next = accepted(preview.add("New Wave"))
        assertEquals(listOf("Post-Punk", "New Wave"), next.proposed)
        // An approved alias is the same genre key, not a second membership.
        assertEquals("DUPLICATE_GENRE", rejection(next.add("new-wave")))
        assertEquals("DUPLICATE_GENRE", rejection(preview.add("post punk")))
    }

    @Test
    fun ambiguousLiteralLabelsAreNeverSplit() {
        val preview = policy.of(listOf("Rock; Jazz", "Rock/Pop"))
        assertEquals(listOf("Rock; Jazz", "Rock/Pop"), preview.proposed)
        assertEquals(2, preview.proposed.size)
    }

    @Test
    fun placeholdersAndContainersAreNeverAssignedByAnEdit() {
        val preview = policy.of(listOf("Rock"))
        assertEquals("NOT_A_MUSICAL_GENRE", rejection(preview.add("Unknown Genre")))
        assertEquals("NOT_A_MUSICAL_GENRE", rejection(preview.add("Sin género")))
        assertEquals("NOT_A_MUSICAL_GENRE", rejection(preview.add("Other")))
        assertEquals("NOT_A_MUSICAL_GENRE", rejection(preview.add("Various")))
        assertEquals("INVALID_GENRE", rejection(preview.add("   ")))
    }

    @Test
    fun maximumIsThirtyTwoIndependentGenres() {
        val full = policy.of((1..32).map { "Genre $it" })
        assertEquals(32, full.proposed.size)
        assertEquals("MAX_GENRES", rejection(full.add("Genre 33")))
        val preview = policy.of((1..31).map { "Genre $it" })
        assertEquals(32, accepted(preview.add("Genre 32")).proposed.size)
    }

    @Test
    fun missingMarkersFromTheFileStayVisibleUntilExplicitlyRemoved() {
        val preview = policy.of(listOf("Unknown", "Rock"))
        // Nothing disappears silently: the marker is still in original and proposed.
        assertEquals(listOf("Unknown", "Rock"), preview.proposed)
        assertEquals(listOf("Rock"), accepted(preview.removeAt(0)).proposed)
        assertEquals(listOf("Unknown"), accepted(preview.removeAt(1)).proposed)
    }

    @Test
    fun canonicalAliasDiffIsNotASemanticChange() {
        val preview = policy.of(listOf("Dark Wave"))
        val next = accepted(preview.replaceAt(0, "Darkwave"))
        assertEquals(listOf("Darkwave"), next.proposed)
        assertTrue(next.diff().added.isEmpty())
        assertTrue(next.diff().removed.isEmpty())
    }
}
