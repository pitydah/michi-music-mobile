package org.michimusic.mobile.artist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MusicBrainzCreditJsonTest {
    private val rec = "11111111-1111-4111-8111-111111111111"
    private val rel = "22222222-2222-4222-8222-222222222222"
    private val a = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val b = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private fun obj(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    @Test fun joinsAndDisplayNamesAreNotParsedAsIdentities() {
        val j = obj("""{"id":"$rec","artist-credit":[{"artist":{"id":"$a"}}," feat. ",{"artist":{"id":"$b"}}]}""")
        assertEquals(MusicBrainzRecordingCredit(rec, setOf(a, b)), MusicBrainzCreditJson.recording(j))
    }
    @Test fun exactReleaseRecordingRelationshipIsExtracted() {
        val j = obj("""{"id":"$rel","artist-credit":[{"artist":{"id":"$a"}}],
          "media":[{"tracks":[{"recording":{"id":"$rec"}},{"recording":{"id":"$b"}}]}]}""")
        assertEquals(MusicBrainzReleaseCredit(rel,setOf(a),setOf(rec,b)), MusicBrainzCreditJson.release(j))
    }
    @Test fun releaseTrackArtistCreditIsNotInterchangeableWithAlbumCredit() {
        val j = obj("""{"id":"$rel","artist-credit":[{"artist":{"id":"$a"}}],
          "media":[{"tracks":[{"recording":{"id":"$rec"},
            "artist-credit":[{"artist":{"id":"$b"}}]}]}]}""")
        assertEquals(setOf(b), MusicBrainzCreditJson.release(j)?.trackArtistMbids?.get(rec))
    }
    @Test fun invalidReleaseTrackCreditFailsClosed() {
        val j = obj("""{"id":"$rel","artist-credit":[{"artist":{"id":"$a"}}],
          "media":[{"tracks":[{"recording":{"id":"$rec"},
            "artist-credit":[{"artist":{"name":"No MBID"}}]}]}]}""")
        assertNull(MusicBrainzCreditJson.release(j))
    }
    @Test fun conflictingRepeatedReleaseTrackCreditsFailClosed() {
        val j = obj("""{"id":"$rel","artist-credit":[{"artist":{"id":"$a"}}],
          "media":[{"tracks":[{"recording":{"id":"$rec"},
            "artist-credit":[{"artist":{"id":"$a"}}]},
            {"recording":{"id":"$rec"},"artist-credit":[{"artist":{"id":"$b"}}]}]}]}""")
        assertNull(MusicBrainzCreditJson.release(j))
    }
    @Test fun missingRecordingIdsNeverProduceFalseProof() {
        val j = obj("""{"id":"$rel","artist-credit":[{"artist":{"id":"$a"}}],
          "media":[{"tracks":[{"title":"No MBID"}]}]}""")
        assertNull(MusicBrainzCreditJson.release(j))
    }
    @Test fun oversizedReleaseIsRejected() {
        val media = (1..26).joinToString(",") { """{"tracks":[]}""" }
        assertNull(MusicBrainzCreditJson.release(obj("""{"id":"$rel","artist-credit":[{"artist":{"id":"$a"}}],"media":[$media]}""")))
    }

    @Test fun moreThanSixteenArtistCreditsCannotBecomeSoloDueToTruncation() {
        val many = (1..17).joinToString(",") { """{"artist":{"id":"$a"}}""" }
        assertNull(MusicBrainzCreditJson.recording(obj("""{"id":"$rec","artist-credit":[$many]}""")))
    }
    @Test fun malformedSecondCreditNeverBecomesSoloArtist() {
        assertNull(MusicBrainzCreditJson.recording(obj("""{"id":"$rec","artist-credit":[
          {"artist":{"id":"$a"}}," feat. ",{"artist":{"name":"Unknown"}}]}""")))
    }
    @Test fun duplicateArtistEntryFailsClosed() {
        assertNull(MusicBrainzCreditJson.recording(obj("""{"id":"$rec","artist-credit":[
          {"artist":{"id":"$a"}},{"artist":{"id":"$a"}}]}""")))
    }
    @Test fun trailingJoinPhraseDoesNotFakeAnApparentlySoloArtist() {
        assertNull(MusicBrainzCreditJson.recording(obj("""{"id":"$rec","artist-credit":[
            {"artist":{"id":"$a"}}," feat. "]}""")))
    }
    @Test fun leadingJoinPhraseDoesNotFakeAnApparentlySoloArtist() {
        assertNull(MusicBrainzCreditJson.recording(obj("""{"id":"$rec","artist-credit":[
            " & ",{"artist":{"id":"$a"}}]}""")))
    }
    @Test fun releaseMissingCreditCannotProveAlbumArtist() {
        assertNull(MusicBrainzCreditJson.release(obj("""{"id":"$rel","media":[{"tracks":[
          {"recording":{"id":"$rec"}}]}]}""")))
    }
    @Test fun malformedReleaseRecordingCannotBeSilentlyDropped() {
        assertNull(MusicBrainzCreditJson.release(obj("""{"id":"$rel","artist-credit":[
          {"artist":{"id":"$a"}}],"media":[{"tracks":[
          {"recording":{"id":"$rec"}},{"recording":{"title":"Unknown"}}]}]}""")))
    }
    @Test fun artistObjectTruncatedJoinphraseCannotProveSoloRecording() {
        val raw = """{"id":"$rec","artist-credit":[{"artist":{"id":"$a"},"joinphrase":" feat. "}]}"""
        assertNull(MusicBrainzCreditJson.recording(obj(raw)))
    }
    @Test fun artistObjectTruncatedJoinphraseCannotProveSoloAlbum() {
        val raw = """{"id":"$rel","artist-credit":[{"artist":{"id":"$a"},"joinphrase":" & "}],
          "media":[{"tracks":[{"recording":{"id":"$rec"}}]}]}"""
        assertNull(MusicBrainzCreditJson.release(obj(raw)))
    }
    @Test fun legitimateObjectJoinphraseBetweenTwoArtistsRemainsSupported() {
        val raw = """{"id":"$rec","artist-credit":[{"artist":{"id":"$a"},"joinphrase":" & "},
          {"artist":{"id":"$b"},"joinphrase":""}]}"""
        assertEquals(setOf(a,b), MusicBrainzCreditJson.recording(obj(raw))?.artistMbids)
    }
    @Test fun malformedIdentifierCannotBeProof() {
        assertNull(MusicBrainzCreditJson.recording(obj("""{"id":"../$rec","artist-credit":[
          {"artist":{"id":"$a"}}]}""")))
    }
    @Test fun threeValidArtistCreditEntriesProduceReviewableCollaboration() {
        val j = obj("""{"id":"$rec","artist-credit":[{"artist":{"id":"$a"}},
          " & ",{"artist":{"id":"$b"}}]}""")
        assertEquals(setOf(a, b), MusicBrainzCreditJson.recording(j)?.artistMbids)
    }
}
