package org.michimusic.mobile.ui.components

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

class MetadataMusicBrainzSuggestionsTest {
    private val id = "f5f9efb8-e9a7-45db-9aec-0ffbef2d9164"
    private val release = "e1a93c4f-46c0-42d7-90e1-e63e491c52f7"
    private val artist = "47eb1f69-7d19-4cec-8de3-25aa6bf5df80"
    private fun obj(data: String) = Json.parseToJsonElement(data) as JsonObject
    private val response get() = obj("""{"recordings":[{"id":"$id","title":"Noches de jazz",
      "score":94,"artist-credit":[{"name":"Gato Azul","artist":{"id":"$artist","name":"Gato Azul"}}],
      "releases":[{"id":"$release","title":"Álbum Original","date":"2026-09-01"}]}]}""")
    private fun snap(allowed: Set<MetadataField>) = MetadataSnapshot(
        MetadataTarget("", "t", "content://fixture/t", "verified"), true, emptyMap(), verifiedFields = allowed)

    @Test fun recordingAndReleaseAreSeparateIdentities() {
        val result=MetadataMusicBrainzSuggestions.parse(response).single()
        assertEquals(id, result.recordingMbid)
        assertEquals(release, result.releaseMbid)
        assertEquals(listOf("Álbum Original"), result.fields[MetadataField.ALBUM])
        assertEquals(listOf("2026-09-01"), result.fields[MetadataField.RELEASE_DATE])
    }
    @Test fun hasNoInventedGenreIsrcOrReleaseTrackId() {
        val fields=MetadataMusicBrainzSuggestions.parse(response).single().fields
        assertFalse(MetadataField.GENRE in fields)
        assertFalse(MetadataField.ISRC in fields)
        assertFalse(MetadataField.MUSICBRAINZ_RELEASE_TRACK_ID in fields)
    }
    @Test fun excludesFieldsNotPhysicallyVerified() {
        val result=MetadataMusicBrainzSuggestions.parse(response).single()
        val eligible=MetadataMusicBrainzSuggestions.reviewableFields(snap(setOf(MetadataField.TITLE)), result)
        assertEquals(setOf(MetadataField.TITLE), eligible.keys)
    }
    @Test fun invalidUuidAndGiantResponseRejected() {
        assertTrue(MetadataMusicBrainzSuggestions.parse(obj("""{"recordings":[{"id":"bad","title":"X"}]}""")).isEmpty())
        assertTrue(MetadataMusicBrainzSuggestions.parse(obj("""{"recordings":[${List(9){"{}"}.joinToString(",")}]}""")).isEmpty())
    }
    @Test fun onlyAfterExplicitGatewayInvocation() = runBlocking {
        var requests=0
        val gateway=MetadataMusicBrainzSuggestions { requests++; response }
        assertEquals(0,requests)
        val prepared=MichiGenreNormalizationPolicy.prepareSearch("Noches de jazz", "Gato Azul")!!
        assertEquals(1,gateway.search(prepared).size)
        assertEquals(1,requests)
    }
    @Test fun rejectsUnpreparedRequestBeforeNetwork() = runBlocking {
        var calls=0
        val service=MetadataMusicBrainzSuggestions { calls++; response }
        try { service.search(MichiGenreNormalizationPolicy.SearchIntent("", "")); throw AssertionError() }
        catch (_: IllegalArgumentException) {}
        assertEquals(0,calls)
    }
    @Test fun creditsJoinTextIsNotSilentlySplitIntoTwoArtists() {
        val json=obj("""{"recordings":[{"id":"$id","title":"Song","artist-credit":
          [{"name":"A","artist":{"id":"$artist"}}," feat. ",{"name":"B"}]}]}""")
        assertEquals(listOf("A feat. B"),MetadataMusicBrainzSuggestions.parse(json).single().fields[MetadataField.ARTIST])
    }
}
