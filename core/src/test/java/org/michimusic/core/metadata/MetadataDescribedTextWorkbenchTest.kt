package org.michimusic.core.metadata

import org.junit.Assert.*
import org.junit.Test

class MetadataDescribedTextWorkbenchTest {
    private val kind = MetadataId3DescribedTagCodec.Kind.COMMENT
    private val english = MetadataDescribedTextWorkbench.Identity(kind, "eng", "")
    private val spanish = MetadataDescribedTextWorkbench.Identity(kind, "spa", "Notas")
    private val source = listOf(
        MetadataDescribedTextWorkbench.Item(english, "English comment"),
        MetadataDescribedTextWorkbench.Item(spanish, "Comentario original"),
    )
    private fun review(changes: List<MetadataDescribedTextWorkbench.Change>) =
        MetadataDescribedTextWorkbench.review(true, source, changes)

    @Test fun editsExactLanguageWithoutChangingOther() {
        val result = review(listOf(MetadataDescribedTextWorkbench.Change.Replace(spanish, "Nuevo comentario")))
            as MetadataDescribedTextWorkbench.Result.Ready
        assertEquals("English comment", result.values.single { it.identity == english }.text)
        assertEquals("Nuevo comentario", result.values.single { it.identity == spanish }.text)
        assertEquals(1, result.differences.size)
    }
    @Test fun clearRequiresExplicitOperation() {
        val result = review(listOf(MetadataDescribedTextWorkbench.Change.Clear(spanish)))
            as MetadataDescribedTextWorkbench.Result.Ready
        assertEquals(listOf(english), result.values.map { it.identity })
    }
    @Test fun emptyTextIsNotClear() {
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.INVALID_TEXT),
            review(listOf(MetadataDescribedTextWorkbench.Change.Replace(spanish, ""))))
    }
    @Test fun unknownEntryIsBlocked() {
        val other = spanish.copy(description = "Otra")
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.UNKNOWN_ENTRY),
            review(listOf(MetadataDescribedTextWorkbench.Change.Clear(other))))
    }
    @Test fun absentEvidenceIsBlocked() {
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.UNVERIFIED_SOURCE),
            MetadataDescribedTextWorkbench.review(false, source, emptyList()))
    }
    @Test fun duplicatedEntryIsBlocked() {
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.DUPLICATE_ORIGINAL),
            MetadataDescribedTextWorkbench.review(true, source + source.first(), emptyList()))
    }
    @Test fun duplicatedOperationIsBlocked() {
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.DUPLICATE_OPERATION),
            review(listOf(MetadataDescribedTextWorkbench.Change.Clear(spanish), MetadataDescribedTextWorkbench.Change.Clear(spanish))))
    }
    @Test fun invalidLanguageIsBlocked() {
        val identity = spanish.copy(language = "ES")
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.INVALID_IDENTITY),
            review(listOf(MetadataDescribedTextWorkbench.Change.Clear(identity))))
    }
    @Test fun cannotCreateUnobservedFrame() {
        val identity = spanish.copy(description = "Nuevo")
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.AMBIGUOUS_CREATE),
            MetadataDescribedTextWorkbench.proposeNew(identity, "texto"))
    }
    @Test fun noopDoesNotMakeDiff() {
        val result = review(listOf(MetadataDescribedTextWorkbench.Change.Replace(english, "English comment")))
            as MetadataDescribedTextWorkbench.Result.Ready
        assertTrue(result.differences.isEmpty())
    }
    @Test fun malformedOriginalBlocksAllEdits() {
        val invalid = source + MetadataDescribedTextWorkbench.Item(spanish.copy(description = "a\u0000b"), "x")
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.INVALID_IDENTITY),
            MetadataDescribedTextWorkbench.review(true, invalid, emptyList()))
    }
    @Test fun invalidControlCharacterBlocks() {
        assertEquals(MetadataDescribedTextWorkbench.Result.Blocked(MetadataDescribedTextWorkbench.Reason.INVALID_TEXT),
            review(listOf(MetadataDescribedTextWorkbench.Change.Replace(spanish, "a\u0001b"))))
    }
}
