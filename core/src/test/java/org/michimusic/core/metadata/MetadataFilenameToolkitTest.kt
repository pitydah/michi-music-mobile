package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFilenameToolkitTest {
    private fun original(id: String = "one", tags: Map<MetadataField,List<String>> = mapOf(
        MetadataField.TITLE to listOf("Noches de jazz"),
        MetadataField.ARTIST to listOf("Gato Azul"),
        MetadataField.TRACK_NUMBER to listOf("03"),
    ), verified:Boolean = true) = MetadataSnapshot(
        MetadataTarget("A", id, "content://lib/$id", "proof-$id"), verified, tags,
        verifiedFields = MetadataField.entries.toSet())

    @Test fun extractsFilenameWithLiteralSeparators() {
        val res=MetadataFilenameToolkit.tagsFromFilename("03 - Gato Azul - Noches de jazz.flac",
            "%track% - %artist% - %title%") as MetadataFilenameToolkit.Result.Success
        assertEquals(listOf(MetadataChange.Replace(MetadataField.TRACK_NUMBER,listOf("03")),
            MetadataChange.Replace(MetadataField.ARTIST,listOf("Gato Azul")),
            MetadataChange.Replace(MetadataField.TITLE,listOf("Noches de jazz"))),res.value)
    }

    @Test fun preservesInternalGenreSeparators() {
        val res=MetadataFilenameToolkit.tagsFromFilename("R&B/Soul.flac","%genre%")
        // A filename is not permitted to contain path separators on Android; do not guess.
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.INVALID_FILENAME),res)
        val okay=MetadataFilenameToolkit.tagsFromFilename("Jazz; Fusion.mp3","%genre%") as MetadataFilenameToolkit.Result.Success
        assertEquals(listOf("Jazz; Fusion"),(okay.value.single() as MetadataChange.Replace).values)
    }

    @Test fun rejectsUnknownOrAdjacentTokens() {
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.UNKNOWN_TOKEN),
            MetadataFilenameToolkit.tagsFromFilename("Song.mp3","%foobar%"))
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.AMBIGUOUS_TOKENS),
            MetadataFilenameToolkit.tagsFromFilename("Track.mp3","%artist%%title%"))
    }

    @Test fun rejectsDuplicateFieldsInTemplate() {
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.DUPLICATE_TOKEN),
            MetadataFilenameToolkit.tagsFromFilename("X - Y.flac","%title% - %title%"))
    }

    @Test fun doesNotInventTagsOnNoMatch() {
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.NO_MATCH),
            MetadataFilenameToolkit.tagsFromFilename("Song.mp3","%artist% - %title%"))
    }

    @Test fun generatesReadOnlyRenameProposal() {
        val res=MetadataFilenameToolkit.proposeRename(original(),"old.flac","%track% - %artist% - %title%")
            as MetadataFilenameToolkit.Result.Success
        assertEquals("03 - Gato Azul - Noches de jazz.flac",res.value.after)
        assertTrue(res.value.providerCollisionCheckRequired)
    }

    @Test fun refusesUnverifiedOrMissingTags() {
        val source=original(verified=false)
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.UNVERIFIED_TAGS),
            MetadataFilenameToolkit.proposeRename(source,"old.flac","%title%"))
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.MISSING_TAG),
            MetadataFilenameToolkit.proposeRename(original(),"old.flac","%album%"))
    }

    @Test fun refusesAmbiguousArtistsWithoutPickingFirst() {
        val input=original(tags=mapOf(MetadataField.ARTIST to listOf("A","B")))
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.MULTI_VALUE_AMBIGUOUS),
            MetadataFilenameToolkit.proposeRename(input,"old.flac","%artist%"))
    }

    @Test fun refusesUnsafePathAndControlChars() {
        val input=original(tags=mapOf(MetadataField.TITLE to listOf("A/B")))
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.UNSAFE_RESULT),
            MetadataFilenameToolkit.proposeRename(input,"old.mp3","%title%"))
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.INVALID_FILENAME),
            MetadataFilenameToolkit.tagsFromFilename("../../other.flac","%title%"))
    }

    @Test fun detectsCaseInsensitiveDestinationCollision() {
        val first=original("1",tags=mapOf(MetadataField.TITLE to listOf("Rock")))
        val second=original("2",tags=mapOf(MetadataField.TITLE to listOf("rock")))
        assertEquals(MetadataFilenameToolkit.Result.Rejected(MetadataFilenameToolkit.Reason.DUPLICATE_DESTINATION),
            MetadataFilenameToolkit.proposeRenameBatch(listOf(first,second),listOf("a.mp3","b.mp3"),"%title%"))
    }

    @Test fun caseSafeBatchKeepsSelectedOrder() {
        val first=original("1",tags=mapOf(MetadataField.TITLE to listOf("Song A")))
        val second=original("2",tags=mapOf(MetadataField.TITLE to listOf("Song B")))
        val proposals=MetadataFilenameToolkit.proposeRenameBatch(listOf(first,second),
            listOf("a.flac","b.flac"),"%title%") as MetadataFilenameToolkit.Result.Success
        assertEquals(listOf("Song A.flac","Song B.flac"),proposals.value.map { it.after })
    }
}
