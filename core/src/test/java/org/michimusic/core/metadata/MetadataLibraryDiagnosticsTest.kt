package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLibraryDiagnosticsTest {
    private fun snap(
        id:String, verified:Boolean=true,
        data:Map<MetadataField,List<String>> = mapOf(
            MetadataField.TITLE to listOf(" A "),
            MetadataField.ARTIST to listOf("B"),
            MetadataField.ALBUM to listOf("X"),
            MetadataField.ALBUM_ARTIST to listOf("B"),
            MetadataField.TRACK_NUMBER to listOf("01"),
        ),
    ) = MetadataSnapshot(MetadataTarget("source",id,"content://music/$id","proof"), verified, data, verifiedFields = MetadataField.entries.toSet())

    @Test fun noGenreNeverCreatesInferredGenre() {
        val item=snap("1")
        val found=MetadataLibraryDiagnostics.inspect(listOf(item))
        assertTrue(found.any { it.kind == MetadataLibraryDiagnostics.Kind.GENRE_NOT_PRESENT })
        assertEquals(null,item.known[MetadataField.GENRE])
    }

    @Test fun unverifiedIsNotMisreportedMissingFields() {
        val res=MetadataLibraryDiagnostics.inspect(listOf(snap("1",verified=false)))
        assertEquals(listOf(MetadataLibraryDiagnostics.Kind.EVIDENCE_UNVERIFIED),res.map{it.kind})
    }

    @Test fun suspiciousSpacesAreSuggestionsNotEdits() {
        val item=snap("1")
        val found=MetadataLibraryDiagnostics.inspect(listOf(item))
        assertTrue(found.any{it.kind == MetadataLibraryDiagnostics.Kind.LEADING_OR_TRAILING_SPACES && it.field == MetadataField.TITLE})
        assertEquals(listOf(" A "),item.known[MetadataField.TITLE])
    }

    @Test fun duplicateNumbersOnlyWithinSameAlbumSource() {
        val same=MetadataLibraryDiagnostics.inspect(listOf(snap("1"),snap("2")))
        assertEquals(2,same.count{it.kind == MetadataLibraryDiagnostics.Kind.DUPLICATE_TRACK_NUMBER_IN_SELECTION})
        val other=snap("2",data=mapOf(
            MetadataField.TITLE to listOf("C"), MetadataField.ALBUM to listOf("Other"),
            MetadataField.ALBUM_ARTIST to listOf("B"), MetadataField.TRACK_NUMBER to listOf("01")))
        val distinct=MetadataLibraryDiagnostics.inspect(listOf(snap("1"),other))
        assertEquals(0,distinct.count{it.kind == MetadataLibraryDiagnostics.Kind.DUPLICATE_TRACK_NUMBER_IN_SELECTION})
    }

    @Test fun doesNotTreatTwoDifferentSourcesAsSameAlbum() {
        val a=snap("1")
        val b=snap("2").copy(target=MetadataTarget("another","2","content://other/2","proof"))
        val res=MetadataLibraryDiagnostics.inspect(listOf(a,b))
        assertEquals(0,res.count{it.kind == MetadataLibraryDiagnostics.Kind.DUPLICATE_TRACK_NUMBER_IN_SELECTION})
    }

    @Test fun incompleteReadNeverClaimsGenreOrAlbumArtistMissing() {
        val partial = snap("a", data = mapOf(MetadataField.TITLE to listOf("Song")))
            .copy(verifiedFields = setOf(MetadataField.TITLE))
        val kinds = MetadataLibraryDiagnostics.inspect(listOf(partial)).map { it.kind }
        assertTrue(MetadataLibraryDiagnostics.Kind.GENRE_NOT_PRESENT !in kinds)
        assertTrue(MetadataLibraryDiagnostics.Kind.ALBUM_ARTIST_NOT_PRESENT !in kinds)
    }

}
