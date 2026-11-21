package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataStructuredPeopleRefreshPolicyTest {
    private fun delta(
        artist: List<String> = listOf("A", "A", "B"),
        albumArtist: List<String> = listOf("X"),
        values: Map<MetadataField, List<String>> = emptyMap(),
        unknown: Set<MetadataField> = emptySet(),
    ): MetadataSourceIndexDelta.Delta {
        val base = MetadataStructuredGenreRefreshPolicy.requiredEvidenceFields
            .associateWith { emptyList<String>() }.toMutableMap()
        base[MetadataField.ARTIST] = artist
        base[MetadataField.ALBUM_ARTIST] = albumArtist
        base[MetadataField.GENRE] = listOf("Jazz; Fusion")
        base.putAll(values)
        return MetadataSourceIndexDelta.Delta(
            MetadataTarget("", "track-1", "content://media/1", "receipt"),
            "txn", "a".repeat(64), base, unknown, 100L)
    }

    @Test fun preservesOrderDuplicatesAndLiteralSemicolon() {
        val p = MetadataStructuredPeopleRefreshPolicy.inspect(delta())
            as MetadataStructuredPeopleRefreshPolicy.Result.Prepared
        assertEquals(listOf("A", "A", "B"), p.artists)
        assertEquals("A\u0000A\u0000B", p.artistsPacked)
        assertEquals("A", p.artist)
        assertFalse(p.sourceWriteAuthorized)
    }

    @Test fun supportsAlbumArtistsOnlyAsMultivalue() {
        val p = MetadataStructuredPeopleRefreshPolicy.inspect(
            delta(artist=listOf("Solo"),albumArtist=listOf("One","Two")))
        assertTrue(p is MetadataStructuredPeopleRefreshPolicy.Result.Prepared)
        assertEquals("One\u0000Two", (p as MetadataStructuredPeopleRefreshPolicy.Result.Prepared).albumArtistsPacked)
    }

    @Test fun rejectsUnverifiedRequiredField() {
        val p=MetadataStructuredPeopleRefreshPolicy.inspect(delta(unknown=setOf(MetadataField.ALBUM)))
        assertEquals(MetadataStructuredPeopleRefreshPolicy.Result.Blocked(
            MetadataStructuredPeopleRefreshPolicy.Reason.PARTIAL_EVIDENCE),p)
    }

    @Test fun rejectsOtherMultivalues() {
        val p=MetadataStructuredPeopleRefreshPolicy.inspect(delta(values=mapOf(
            MetadataField.GENRE to listOf("Jazz","Fusion"))))
        assertTrue(p is MetadataStructuredPeopleRefreshPolicy.Result.Blocked)
    }

    @Test fun rejectsNulAndSurrogates() {
        val nul=MetadataStructuredPeopleRefreshPolicy.inspect(delta(
            artist=listOf("A", "B\u0000C")))
        assertEquals(MetadataStructuredPeopleRefreshPolicy.Result.Blocked(
            MetadataStructuredPeopleRefreshPolicy.Reason.INVALID_VALUES),nul)
        val surrogate=MetadataStructuredPeopleRefreshPolicy.inspect(delta(
            artist=listOf("A", "\uD800")))
        assertTrue(surrogate is MetadataStructuredPeopleRefreshPolicy.Result.Blocked)
    }

    @Test fun refusesAnUnchangedSingleArtistOrForgedHash() {
        val single=MetadataStructuredPeopleRefreshPolicy.inspect(delta(artist=listOf("A")))
        assertTrue(single is MetadataStructuredPeopleRefreshPolicy.Result.Blocked)
        val forged=MetadataStructuredPeopleRefreshPolicy.inspect(delta().copy(sourceSha256="x"))
        assertEquals(MetadataStructuredPeopleRefreshPolicy.Result.Blocked(
            MetadataStructuredPeopleRefreshPolicy.Reason.INVALID_SOURCE),forged)
    }
}
