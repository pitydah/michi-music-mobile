package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EmbeddedTagReadPolicyTest {
    @Test fun successfulGenreIsNotConfusedWithUnverifiable() {
        val fields = EmbeddedTagFields(title = "A", genres = listOf("Pop rock"))
        val result = EmbeddedTagReadPolicy.fromFields(fields)
        assertEquals(true, result is EmbeddedTagReadResult.Parsed)
        assertSame(fields, EmbeddedTagReadPolicy.fieldsOrNull(result))
    }

    @Test fun readableTagsWithoutGenreRemainSeparateFromFailedRead() {
        val fields = EmbeddedTagFields(title = "A")
        assertEquals(true, EmbeddedTagReadPolicy.fromFields(fields) is EmbeddedTagReadResult.NoGenreTag)
        assertEquals(EmbeddedTagReadResult.Unverifiable, EmbeddedTagReadPolicy.fromFields(null))
        assertNull(EmbeddedTagReadPolicy.fieldsOrNull(EmbeddedTagReadResult.ReadFailed))
        assertNull(EmbeddedTagReadPolicy.fieldsOrNull(EmbeddedTagReadResult.PermissionDenied))
        assertNull(EmbeddedTagReadPolicy.fieldsOrNull(EmbeddedTagReadResult.Unverifiable))
    }
}
