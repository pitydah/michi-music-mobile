package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** S1-B3: integrity guard never deletes bytes that no longer match their SHA-256 name. */
class ArtistArtworkCleanupIntegrityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun matchingFileIsEligibleForDeletionGate() {
        val file = folder.newFile("valid.jpg")
        val bytes = "original artist artwork".toByteArray()
        file.writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertTrue(artworkCleanupDigestMatches(file, hash))
    }

    @Test fun changedBytesAreNeverTreatedAsTheJournalAsset() {
        val file = folder.newFile("changed.jpg")
        val before = "verified content".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(before)
            .joinToString("") { "%02x".format(it) }
        file.writeText("replaced content")
        assertFalse(artworkCleanupDigestMatches(file, hash))
    }

    @Test fun missingFileCannotPassTheDeletionGate() {
        assertFalse(artworkCleanupDigestMatches(folder.root.resolve("not-here.jpg"), "0".repeat(64)))
    }
}
