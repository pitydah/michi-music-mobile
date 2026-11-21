package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEditorSectionCatalogTest {
    @Test fun coversEveryDefinedFieldExactlyOnce() {
        val all = MetadataEditorSection.entries.flatMap { it.fields }
        assertEquals(MetadataField.entries.toSet(), all.toSet())
        assertEquals(all.size, all.distinct().size)
    }
    @Test fun sixStableGroupsAndOrder() {
        assertEquals(6, MetadataEditorSection.entries.size)
        assertEquals(MetadataField.TITLE, MetadataEditorSection.BASICS.fields.first())
        assertTrue(MetadataField.GENRE in MetadataEditorSection.BASICS.fields)
        assertTrue(MetadataField.WORK in MetadataEditorSection.CREDITS.fields)
    }
    @Test fun artworkIsNotPretendedToBeTextMetadata() {
        assertTrue(MetadataEditorSection.ART_LYRICS.fields.all { it in MetadataField.entries })
        assertTrue(MetadataField.LYRICS in MetadataEditorSection.ART_LYRICS.fields)
    }
}
