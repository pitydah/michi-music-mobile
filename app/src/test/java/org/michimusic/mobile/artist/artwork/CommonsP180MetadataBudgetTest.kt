package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonsP180MetadataBudgetTest {
    @Test fun fifthQidVerifiedImageRemainsEligibleAfterFourRejectedFiles() {
        val files = (1..12).map { "image-$it" }
        assertEquals(files.take(8), CommonsP180MetadataBudget.selected(files, files.toSet()))
    }

    @Test fun unverifiedAndRepeatedFilesNeverConsumeLegalMetadataBudget() {
        val files = listOf("bad", "one", "one", "foreign", "two", "three")
        assertEquals(listOf("one", "two", "three"),
            CommonsP180MetadataBudget.selected(files, setOf("one", "two", "three")))
    }
}
