package org.michimusic.mobile.intelligence

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.michimusic.core.intelligence.MichiGenreLookupAuthorization
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy

class MichiGenreMusicBrainzGatewayTest {
    private val id = "ae57a2bc-4de1-4e94-9f07-b5301eacdd91"
    private val other = "3a7301f3-87b3-4dfa-9c37-6dabd9536bfd"
    private val policy = MichiGenreNormalizationPolicy
    private val intent get() = policy.prepareSearch("Song", "Artist")!!
    private class Fake(private val searchPayload: String, private val lookupPayload: String):
        MichiGenreMusicBrainzGateway.GenreMusicBrainzReadPort {
        var searches = 0
        var lookups = 0
        override suspend fun search(intent: MichiGenreNormalizationPolicy.SearchIntent): JsonObject {
            searches++
            assertEquals(setOf("recording", "artist"), intent.outgoingFields().keys)
            return Json.parseToJsonElement(searchPayload) as JsonObject
        }
        override suspend fun recording(id: String): JsonObject {
            lookups++
            return Json.parseToJsonElement(lookupPayload) as JsonObject
        }
    }
    private val song get() = """{"id":"$id","title":"Song","artist-credit":[{"name":"Artist"}],"length":240000}"""
    private fun candidate(id: String = this.id) = MichiGenreNormalizationPolicy.RecordingCandidate(id,"Song","Artist")

    @Test fun refusalPerformsZeroNetworkCalls() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val fake = Fake("{}", "{}")
        val gateway = MichiGenreMusicBrainzGateway(fake, auth)
        assertTrue(gateway.search(intent, null).isEmpty())
        assertTrue(gateway.genresForSelectedRecording(candidate(), null).isEmpty())
        assertEquals(0, fake.searches)
        assertEquals(0, fake.lookups)
    }
    @Test fun explicitRecordingSelectionReturnsOnlyGenresAndSourceId() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val fake = Fake("""{"recordings":[$song]}""",
            """{"id":"$id","genres":[{"name":"Progressive Rock","count":7},{"name":"Unknown","count":1},{"name":"Rock","count":3}]}""")
        val gateway = MichiGenreMusicBrainzGateway(fake, auth)
        val recordings = gateway.search(intent, auth.issueSearch(intent), 240000)
        assertEquals(1,recordings.size)
        assertTrue(recordings[0].plausible)
        assertEquals(0,fake.lookups)
        assertTrue(gateway.genresForSelectedRecording(recordings[0].recording,null).isEmpty())
        assertEquals(0,fake.lookups)
        val genres = gateway.genresForSelectedRecording(recordings[0].recording,
            auth.issueRecording(recordings[0].recording.mbid))
        assertEquals(listOf("Progressive Rock", "Rock"),genres.map { it.name })
        assertEquals(id,genres[0].sourceMbid)
        assertEquals(1,fake.lookups)
    }
    @Test fun mismatchedLookupIdentityFailsClosed() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val gateway = MichiGenreMusicBrainzGateway(Fake("{}", """{"id":"$other","genres":[{"name":"Jazz"}]}"""), auth)
        assertTrue(gateway.genresForSelectedRecording(candidate(),auth.issueRecording(id)).isEmpty())
    }
    @Test fun fabricatedSearchIntentDoesNotReachHttpPort() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val fake = Fake("{}", "{}")
        val gateway = MichiGenreMusicBrainzGateway(fake, auth)
        val broken = MichiGenreNormalizationPolicy.SearchIntent("Song\nOther", "Artist")
        val spaced = MichiGenreNormalizationPolicy.SearchIntent(" Song ", "Artist")
        assertTrue(gateway.search(broken, auth.issueSearch(broken)).isEmpty())
        assertTrue(gateway.search(spaced, auth.issueSearch(spaced)).isEmpty())
        assertEquals(0, fake.searches)
    }
    @Test fun duplicateRecordingIdsAreShownOnlyOnce() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val fake = Fake("""{"recordings":[$song,$song]}""", "{}")
        val matches = MichiGenreMusicBrainzGateway(fake, auth).search(intent,auth.issueSearch(intent))
        assertEquals(1, matches.size)
        assertEquals(1, fake.searches)
    }
    @Test fun textJoinTokenMakesArtistCreditUncertain() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val fake = Fake("""{"recordings":[{"id":"$id","title":"Song","artist-credit":[{"name":"Artist"}," feat. "]}]}""", "{}")
        val choices = MichiGenreMusicBrainzGateway(fake, auth).search(intent,auth.issueSearch(intent))
        assertEquals(1, choices.size)
        assertFalse(choices.single().plausible)
    }
    @Test fun malformedOrOversizedProviderGenreListFailsClosed() = runBlocking {
        val oversized = (1..33).joinToString(",") { """{"name":"Genre $it","count":1}""" }
        val auth = MichiGenreLookupAuthorization()
        val all = MichiGenreMusicBrainzGateway(Fake("{}", """{"id":"$id","genres":[$oversized]}"""), auth)
        assertTrue(all.genresForSelectedRecording(candidate(),auth.issueRecording(id)).isEmpty())
        val badVotes = MichiGenreMusicBrainzGateway(Fake("{}", """{"id":"$id","genres":[{"name":"Rock","count":"bad"}]}"""), auth)
        assertTrue(badVotes.genresForSelectedRecording(candidate(),auth.issueRecording(id)).isEmpty())
    }
    @Test fun approvedAliasesFromProviderAreDeduplicatedByCanonicalGenre() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val gateway = MichiGenreMusicBrainzGateway(Fake("{}",
            """{"id":"$id","genres":[{"name":"rockpop","count":1},{"name":"Pop-Rock","count":12},{"name":"Jazz","count":3}]}"""), auth)
        val found = gateway.genresForSelectedRecording(candidate(),auth.issueRecording(id))
        assertEquals(listOf("Pop-Rock", "Jazz"), found.map { it.name })
        assertEquals(12, found.first().votes)
    }
    @Test fun oneShotSearchCannotBeReplayedNorTransferred() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val fake = Fake("""{"recordings":[$song]}""", "{}")
        val gateway = MichiGenreMusicBrainzGateway(fake, auth)
        val grant = auth.issueSearch(intent)
        assertEquals(1, gateway.search(intent, grant).size)
        assertTrue(gateway.search(intent, grant).isEmpty())
        assertEquals(1, fake.searches)
        val otherAuth = MichiGenreLookupAuthorization()
        val otherGateway = MichiGenreMusicBrainzGateway(fake, otherAuth)
        assertTrue(otherGateway.search(intent, auth.issueSearch(intent)).isEmpty())
        assertEquals(1, fake.searches)
    }
    @Test fun oneShotRecordingCannotBeReplayedOrSwapped() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val fake = Fake("{}", """{"id":"$id","genres":[{"name":"Jazz"}]}""")
        val gateway = MichiGenreMusicBrainzGateway(fake, auth)
        val grant = auth.issueRecording(id)
        assertTrue(gateway.genresForSelectedRecording(candidate(other), grant).isEmpty())
        assertTrue(gateway.genresForSelectedRecording(candidate(), grant).isEmpty())
        assertEquals(0, fake.lookups)
        val fresh = auth.issueRecording(id)
        assertEquals(1, gateway.genresForSelectedRecording(candidate(), fresh).size)
        assertTrue(gateway.genresForSelectedRecording(candidate(), fresh).isEmpty())
        assertEquals(1, fake.lookups)
    }
    @Test fun revokedRequestBeforeDispatchSendsNothing() = runBlocking {
        val auth = MichiGenreLookupAuthorization()
        val fake = Fake("{}", "{}")
        val gateway = MichiGenreMusicBrainzGateway(fake, auth)
        val grant = auth.issueSearch(intent)
        auth.revoke() // Dialog closed before suspend function started.
        assertTrue(gateway.search(intent, grant).isEmpty())
        assertEquals(0, fake.searches)
    }
}
