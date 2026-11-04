package org.michimusic.mobile.artist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistPublicDiscographyTest {
    @Test fun excludesMalformedIdsAndNeverFabricatesDates() {
        val json = Json.parseToJsonElement("""{
          "release-group-count":3,
          "release-groups":[
            {"id":"bd6d4f63-5cd0-4d78-a292-167997794c21","title":"Diesel and Dust","first-release-date":"1987-08-02","primary-type":"Album"},
            {"id":"invalid","title":"Ignore me","first-release-date":"1990"},
            {"id":"0b8f1d56-75d5-4b34-a216-004f4c4b5720","title":"Head Injuries"}
          ]
        }""") as JsonObject
        val page = parseMusicBrainzAlbumGroups(json, 0)
        assertEquals(2, page.albums.size)
        assertEquals(1987, page.albums[0].year)
        assertEquals(null, page.albums[1].year)
        assertEquals(3, page.nextOffset)
        assertFalse(page.hasMore)
    }

    @Test fun paginationDoesNotConfuseCountWithReturnedItems() {
        val json = Json.parseToJsonElement("""{"release-group-count":52,
          "release-groups":[{"id":"bd6d4f63-5cd0-4d78-a292-167997794c21","title":"Album"}]}""") as JsonObject
        val page = parseMusicBrainzAlbumGroups(json, 25)
        assertEquals(26, page.nextOffset)
        assertTrue(page.hasMore)
        assertEquals(52, page.total)
    }
}
