package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.PairConfirmRequestDto
import org.michimusic.link.dto.PlaylistCreateRequest
import org.michimusic.link.dto.QueueReorderRequest
import org.michimusic.link.errors.LinkException

class LinkClientTest {
    private fun jsonClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                explicitNulls = false
            })
        }
    }

    /**
     * A67/I02: every AirCat metadata representation must advertise the producer
     * profile and be non-cacheable. Fixtures carry the canonical headers so a
     * negative test fails for its own reason instead of a missing header.
     */
    private fun airCatMetadataHeaders() = headersOf(
        AirCatProducerContractPolicy.HEADER to listOf(AirCatProducerContractPolicy.VERSION),
        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
        HttpHeaders.CacheControl to listOf("no-store"),
    )

    @Test
    fun pairConfirm_withChallengeStrategyOmitsPinOrSendsNull() = runBlocking {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = """
                    {
                      "token":"ed25519-token",
                      "expires_in":3600,
                      "device_id":"client-1",
                      "server_id":"server-1"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:8400",
            httpClient = jsonClient(engine),
        )

        val confirmReq = PairConfirmRequestDto(
            sessionId = "sess-1",
            pin = null, // PIN-less challenge authorization
            michiId = "michi-id-123",
            publicKey = "pk-abc"
        )
        val resp = client.pairConfirm(confirmReq).getOrThrow()
        assertEquals("ed25519-token", resp.token)
        assertTrue(requestBody.contains("sess-1"))
        assertTrue(requestBody.contains("michi-id-123"))
    }

    @Test
    fun pairConfirm_withServerCodeSendsActualPin() = runBlocking {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = """
                    {
                      "token":"code-token",
                      "expires_in":3600,
                      "device_id":"client-1",
                      "server_id":"server-1"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:8400",
            httpClient = jsonClient(engine),
        )

        val confirmReq = PairConfirmRequestDto(
            sessionId = "sess-2",
            pin = "4819",
            michiId = "michi-id-456",
            publicKey = "pk-def"
        )
        val resp = client.pairConfirm(confirmReq).getOrThrow()
        assertEquals("code-token", resp.token)
        assertTrue(requestBody.contains("\"pin\":\"4819\""))
    }

    @Test
    fun fetchSyncManifest_usesNormalizedUrlAndAuthHeaders() = runBlocking {
        var requestedUrl = ""
        var authHeader = ""
        var deviceHeader = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            authHeader = request.headers[HttpHeaders.Authorization].orEmpty()
            deviceHeader = request.headers["X-Michi-Device-Id"].orEmpty()
            respond(
                content = """{"device_id":"phone 1","tracks":[{"track_id":"t1","title":"Song"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318/",
            sessionToken = "session-token",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val result = client.fetchSyncManifest("phone 1")

        assertTrue(result.isSuccess)
        assertEquals("http://127.0.0.1:53318/api/v1/sync/manifest?device_id=phone+1", requestedUrl)
        assertEquals("Bearer device-token", authHeader)
        assertEquals("phone-1", deviceHeader)
        assertEquals("t1", result.getOrThrow().tracks.single().trackId)
    }

    @Test
    fun getServerInfo_parsesDesktopV1Capabilities() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "service":"michi-music-player",
                      "name":"Studio",
                      "api_version":"v1",
                      "michi_link_version":"1.0",
                      "roles":["desktop_player","library_master","sync_host"],
                      "auth":{"required":true,"strategy":"PLAYER_PASSWORD","token_refresh":false},
                      "features":{"library":true,"streaming":true,"token_refresh":false}
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318",
            httpClient = jsonClient(engine),
        )

        val info = client.getServerInfo().getOrThrow()

        assertEquals("michi-music-player", info.service)
        assertEquals("Studio", info.effectiveName)
        assertEquals(false, info.effectiveTokenRefresh)
        assertEquals(false, client.tokenRefreshSupported)
        assertTrue(info.roles.contains("sync_host"))
    }

    @Test
    fun pairConfirm_acceptsDesktopResponseWithoutRefreshToken() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "success":true,
                      "device_id":"phone-1",
                      "device_token":"device-token",
                      "permissions":["library.read","stream.read","sync.read_manifest"],
                      "server_device_id":"desktop-1",
                      "server_alias":"Studio"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318",
            httpClient = jsonClient(engine),
        )

        val result = client.pairConfirmLegacy(
            pairingId = "pair-1",
            username = "michi",
            password = "secret",
            clientDeviceId = "phone-1",
            alias = "Pixel",
            deviceModel = "Android",
        ).getOrThrow()

        assertEquals("device-token", result.deviceToken)
        assertEquals("device-token", client.deviceToken)
        assertTrue(result.refreshToken.isEmpty())
    }

    @Test
    fun fetchLibrary_normalizesDesktopTrackIds() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"tracks":[{"track_id":"desk-1","title":"Desktop Song"}],"total":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val library = client.fetchLibrary().getOrThrow()

        assertEquals(1, library.total)
        assertEquals("desk-1", library.tracks.single().id)
        assertEquals("desk-1", library.tracks.single().effectiveId)
    }

    @Test
    fun fetchSearch_normalizesDesktopTrackIds() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"results":[{"track_id":"desk-2","title":"Search Song"}],"query":"Search"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val results = client.search("Search").getOrThrow()

        assertEquals("desk-2", results.single().id)
        assertEquals("Search Song", results.single().title)
    }

    @Test
    fun fetchSyncManifestDelta_parsesDesktopModifiedAndRemovedObjects() = runBlocking {
        var requestedUrl = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = """
                    {
                      "manifest_id":"m2",
                      "device_id":"phone-1",
                      "since":"m1",
                      "until":"m2",
                      "added":[{"track_id":"new-1","title":"New"}],
                      "modified":[{"track_id":"mod-1","title":"Changed"}],
                      "removed":[{"track_id":"old-1","title":"Old"}],
                      "total_added":1,
                      "total_modified":1,
                      "total_removed":1
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val delta = client.fetchSyncManifestDelta("phone 1", "m1").getOrThrow()

        assertEquals("http://127.0.0.1:53318/api/v1/sync/manifest/delta?device_id=phone+1&cursor=m1", requestedUrl)
        assertEquals(listOf("new-1", "mod-1"), delta.effectiveTracks.map { it.trackId })
        assertEquals(listOf("old-1"), delta.effectiveRemoved)
        assertEquals(1, delta.totalModified)
    }

    @Test
    fun refreshToken_mapsDesktopNotImplemented() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"error":{"code":"NOT_IMPLEMENTED","message":"Token refresh is not supported"}}""",
                status = HttpStatusCode.NotImplemented,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val result = client.refreshToken("refresh-token", force = true)

        assertTrue(result.isFailure)
        assertEquals(LinkException.NotImplemented, result.exceptionOrNull())
        assertEquals(false, client.tokenRefreshSupported)
    }

    @Test
    fun getQueue_supportsLegacyItemsPayload() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"items":[{"track_id":"t1","title":"Legacy Track"}],"currentIndex":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318",
            deviceToken = "device-token",
            httpClient = jsonClient(engine),
        )

        val queue = client.getQueue().getOrThrow()

        assertEquals(0, queue.currentIndex)
        assertEquals("t1", queue.tracks.single().trackId)
        assertEquals("Legacy Track", queue.tracks.single().title)
    }

    @Test
    fun fetchAlbums_parsesServerResponse() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"items":[{"id":"a1","title":"Abbey Road","artist":"The Beatles"}],"total":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val albums = client.fetchAlbums().getOrThrow()
        assertEquals(1, albums.total)
        assertEquals("Abbey Road", albums.items.single().title)
    }

    @Test
    fun fetchAlbum_detailReturnsTracks() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"album":{"id":"a1","title":"Abbey Road"},"tracks":[{"track_id":"t1","title":"Come Together"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val detail = client.fetchAlbum("a1").getOrThrow()
        assertEquals("Abbey Road", detail.album.title)
        assertEquals("t1", detail.tracks.single().trackId)
    }

    @Test
    fun fetchArtists_parsesServerResponse() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"items":[{"id":"art1","name":"The Beatles","album_count":13}],"total":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val artists = client.fetchArtists().getOrThrow()
        assertEquals(1, artists.total)
        assertEquals("The Beatles", artists.items.single().name)
    }

    @Test
    fun createAndFetchPlaylist_roundtrip() = runBlocking {
        var created = false
        val engine = MockEngine { request ->
            if (!created) {
                created = true
                respond(
                    content = """{"id":"pl1","name":"Favorites","track_count":0,"tracks":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    content = """{"id":"pl1","name":"Favorites","track_count":1,"tracks":[{"track_id":"t1","title":"Test"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val createdPlaylist = client.createPlaylist(PlaylistCreateRequest("Favorites")).getOrThrow()
        assertEquals("pl1", createdPlaylist.id)
        val fetchedPlaylist = client.fetchPlaylist("pl1").getOrThrow()
        assertEquals(1, fetchedPlaylist.trackCount)
    }

    @Test
    fun starAndRateTrack_success() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"id":"t1","starred":true,"starred_at":"2026-07-18T12:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val star = client.starTrack("t1").getOrThrow()
        assertTrue(star.starred)
    }

    @Test
    fun fetchReceivers_parsesList() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """[{"id":"r1","name":"Living Room","device_type":"hifi","volume":70,"is_active":true}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val receivers = client.fetchReceivers().getOrThrow()
        assertEquals("Living Room", receivers.single().name)
        assertEquals(70, receivers.single().volume)
    }

    @Test
    fun parsePlaybackStateWithTrackId() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"state":"playing","current_track":{"track_id":"t1","title":"Song1"},"queue_id":"q1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val state = client.getPlaybackState().getOrThrow()
        assertEquals("playing", state.effectiveState)
        assertEquals("Song1", state.effectiveTitle)
        assertEquals("q1", state.queueId)
    }

    @Test
    fun parseQueueWithQueueId() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"tracks":[{"track_id":"t1","title":"Song1"}],"current_index":0,"queue_id":"q1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val queue = client.getQueue().getOrThrow()
        assertEquals("q1", queue.queueId)
        assertEquals("t1", queue.tracks.single().trackId)
    }

    @Test
    fun fetchHistory_parsesEntries() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"items":[{"track_id":"t1","played_at":"2026-07-18T10:00:00Z"}],"page":1,"total":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val history = client.fetchHistory().getOrThrow()
        assertEquals(1, history.total)
        assertEquals("t1", history.items.single().trackId)
    }

    @Test
    fun reorderQueue_sendsCorrectPayload() = runBlocking {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = """{}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        client.reorderQueue(QueueReorderRequest(fromIndex = 2, toIndex = 0)).getOrThrow()
        assertTrue(requestBody.contains("from_index"))
        assertTrue(requestBody.contains("to_index"))
    }

    @Test
    fun diagnostics_usesApiV1() = runBlocking {
        var requestedUrl = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = """{"status":"ok","version":"0.2.0","uptime_secs":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        client.fetchServerDiagnostics().getOrThrow()
        assertTrue(requestedUrl.contains("/api/v1/diagnostics"))
    }

    @Test
    fun rangeValidatorRejectsWrongOffsetAndChangedEtag() {
        assertTrue(validateStreamRangeHeaders(5, 206, "bytes 4-9/10", 6, "\"v1\"", "\"v1\"") != null)
        assertTrue(validateStreamRangeHeaders(5, 206, "bytes 5-9/10", 5, "\"v1\"", "\"v2\"") != null)
        assertTrue(validateStreamRangeHeaders(5, 206, "bytes 5-9/10", 4, null, "") != null)
        assertEquals(null, validateStreamRangeHeaders(5, 206, "bytes 5-9/10", 5, "\"v1\"", "\"v1\""))
    }

    @Test
    fun malformedPartialDoesNotWriteBytes() = runBlocking {
        val engine = MockEngine {
            respond("INVALID", HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 0-6/20"))
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val out = java.io.ByteArrayOutputStream()
        val result = client.streamTrackRange("remote-audio", out, startBytes = 7L)
        assertTrue(result.isFailure)
        assertEquals(0, out.size())
    }

    @Test
    fun resumeIgnoredByServerDoesNotAppendBodyAndPreservesQuotedEtag() = runBlocking {
        var seenIfRange = ""
        val engine = MockEngine { request ->
            seenIfRange = request.headers["If-Range"].orEmpty()
            respond("FULL_TRACK", HttpStatusCode.OK, headersOf("ETag", "\"file-v1\""))
        }
        val client = LinkClient.createForTest("http://127.0.0.1:53318", deviceToken = "token", httpClient = jsonClient(engine))
        val out = java.io.ByteArrayOutputStream()
        val response = client.streamTrackRange("remote-audio", out, startBytes = 7L, expectedEtag = "\"file-v1\"").getOrThrow()
        assertEquals("\"file-v1\"", seenIfRange)
        assertEquals("\"file-v1\"", response.etag)
        assertEquals(0, out.size())
        assertFalse(response.isPartialContent)
    }

    @Test
    fun managedOriginalSource_usesRawDownloadWithoutTranscodeOrFallback() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls.add(request.url.encodedPath)
            if (request.url.encodedPath.startsWith("/api/v1/stream/")) {
                respond("TRANSCODED", HttpStatusCode.OK)
            } else {
                respond("original-bytes", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "14"))
            }
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318", deviceToken = "limited-token",
            httpClient = jsonClient(engine),
        )
        val raw = java.io.ByteArrayOutputStream()
        val managed = client.originalFileSource().streamTrackRange("file-id", raw)
        assertTrue(managed.isSuccess)
        assertEquals("original-bytes", raw.toString("UTF-8"))
        assertEquals(listOf("/api/v1/download/file-id"), calls)
        val playback = java.io.ByteArrayOutputStream()
        assertTrue(client.streamTrackRange("file-id", playback).isSuccess)
        assertEquals("TRANSCODED", playback.toString("UTF-8"))
        assertEquals(listOf("/api/v1/download/file-id", "/api/v1/stream/file-id"), calls)
    }

    @Test
    fun managedOriginalSource_missingRawEndpointNeverRequestsTranscodingEndpoint() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls.add(request.url.encodedPath)
            respond("No original source", HttpStatusCode.NotFound)
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318", deviceToken = "limited-token",
            httpClient = jsonClient(engine),
        )
        val bytes = java.io.ByteArrayOutputStream()
        val result = client.originalFileSource().streamTrackRange("remote-track", bytes)
        assertTrue(result.isFailure)
        assertEquals(0, bytes.size())
        assertEquals(listOf("/api/v1/download/remote-track"), calls)
    }

    @Test
    fun managedOriginalSource_partialRangeStaysWithinOriginalDownloadRoute() = runBlocking {
        val calls = mutableListOf<String>()
        val ranges = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls.add(request.url.encodedPath)
            ranges.add(request.headers[HttpHeaders.Range].orEmpty())
            respond("second", HttpStatusCode.PartialContent,
                io.ktor.http.headers {
                    append(HttpHeaders.ContentRange, "bytes 5-10/11")
                    append(HttpHeaders.ContentLength, "6")
                    append(HttpHeaders.ETag, "\"strong\"")
                })
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318", deviceToken = "limited-token",
            httpClient = jsonClient(engine),
        )
        val bytes = java.io.ByteArrayOutputStream()
        val result = client.originalFileSource().streamTrackRange(
            "remote-track", bytes, startBytes = 5L, expectedEtag = "\"strong\"",
        )
        assertTrue(result.isSuccess)
        assertEquals("second", bytes.toString("UTF-8"))
        assertEquals(listOf("bytes=5-"), ranges)
        assertEquals(listOf("/api/v1/download/remote-track"), calls)
    }
    @Test
    fun rawDownload_doesNotFollowRedirectOrLeakBearer() = runBlocking {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            if (requests == 1) {
                assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                assertTrue(request.url.toString().endsWith("/api/v1/download/remote-1"))
                respond(content = "", status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "http://untrusted.invalid/steal"))
            } else {
                throw AssertionError("A25: redirected bearer reached a second request")
            }
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318", deviceToken = "secret",
            httpClient = jsonClient(engine),
        )
        val result = client.originalFileSource().streamTrackRange(
            "remote-1", java.io.ByteArrayOutputStream())
        assertTrue(result.isFailure)
        assertEquals(1, requests)
        client.close()
    }

    @Test
    fun airCatAuthorize_usesPinnedHttpsApiContractAndBearer() = runBlocking {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("https", request.url.protocol.name)
            assertEquals("/api/v1/aircat/authorize", request.url.encodedPath)
            assertEquals("Bearer home-session", request.headers[HttpHeaders.Authorization])
            assertTrue(request.body.toByteArray().decodeToString().contains("transferId"))
            respond("{\"version\":1}", HttpStatusCode.OK,
                airCatMetadataHeaders())
        }
        val client = LinkClient.createForTest(
            baseUrl="https://home.example", deviceToken="home-session", httpClient=jsonClient(engine),
            assumePinnedAirCatForTest=true)
        val result=client.requestAirCatAuthorization("{\"transferId\":\"tx\"}")
        assertTrue(result.isSuccess)
        assertEquals("{\"version\":1}", result.getOrThrow())
        assertEquals(1, calls)
        client.close()
    }

    @Test
    fun airCatAuthorize_rejectsPlainHttpBeforeSendingBearer() = runBlocking {
        var calls=0
        val engine=MockEngine { calls++;respond("forbidden",HttpStatusCode.Forbidden) }
        val client=LinkClient.createForTest(
            baseUrl="http://192.168.1.50:8400",deviceToken="secret",httpClient=jsonClient(engine))
        val result=client.requestAirCatAuthorization("{\"transferId\":\"tx\"}")
        assertTrue(result.isFailure)
        assertEquals(0,calls)
        client.close()
    }

    @Test
    fun airCatAuthorize_rejectsUnpinnedHttpsBeforeNetwork() = runBlocking {
        var calls = 0
        val engine = MockEngine { calls++;respond("should not send", HttpStatusCode.OK) }
        val client = LinkClient.createForTest(
            baseUrl="https://home.example",deviceToken="secret",httpClient=jsonClient(engine))
        assertTrue(client.requestAirCatAuthorization("{\"transferId\":\"tx\"}").isFailure)
        assertEquals(0, calls)
        client.close()
    }

    @Test
    fun airCatAuthorize_rejectsRedirectWithoutFollowing() = runBlocking {
        var calls=0
        val engine=MockEngine { request ->
            calls++
            if (calls != 1) throw AssertionError("Bearer leaked via 302")
            respond("",HttpStatusCode.Found,
                headersOf(HttpHeaders.Location,"https://attacker.example/steal"))
        }
        val http=HttpClient(engine) { followRedirects=false }
        val client=LinkClient.createForTest(
            baseUrl="https://home.example", deviceToken="secret",httpClient=http,
            assumePinnedAirCatForTest=true)
        assertTrue(client.requestAirCatAuthorization("{\"transferId\":\"tx\"}").isFailure)
        assertEquals(1,calls)
        client.close()
    }

    @Test
    fun airCatAuthorize_doesNotUseLegacyDownloadOnMissingEndpoint() = runBlocking {
        val paths=mutableListOf<String>()
        val engine=MockEngine { request ->
            paths+=request.url.encodedPath
            respond("not found",HttpStatusCode.NotFound)
        }
        val client=LinkClient.createForTest(
            baseUrl="https://home.example",deviceToken="secret",httpClient=jsonClient(engine),
            assumePinnedAirCatForTest=true)
        val result=client.requestAirCatAuthorization("{\"transferId\":\"tx\"}")
        assertTrue(result.isFailure)
        assertEquals(listOf("/api/v1/aircat/authorize"),paths)
        client.close()
    }

    @Test
    fun playbackStream_keepsLegacyEndpointAfterRawRedirectHardening() = runBlocking {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respond(content = "abc", status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "3"))
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:53318", httpClient = jsonClient(engine))
        val result = client.streamTrackRange("track-1", java.io.ByteArrayOutputStream())
        assertTrue(result.isSuccess)
        assertEquals(listOf("/api/v1/stream/track-1"), paths)
        client.close()
    }

    @Test
    fun airCatManifest_requiresPinnedHttpsBeforeAnyRequest() = runBlocking {
        var calls = 0
        val client = LinkClient.createForTest(
            baseUrl="https://home.example", deviceToken="secret",
            httpClient=jsonClient(MockEngine { calls++;respond("{}",HttpStatusCode.OK) }))
        assertTrue(client.fetchAirCatManifest().isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun airCatManifest_usesDedicatedOriginalFileCatalogAndValidatesHash() = runBlocking {
        var requested = ""
        val engine = MockEngine { request ->
            requested = request.url.encodedPath
            respond("""{"schema":"michi.aircat.manifest.v1","version":1,
                "device_id":"source-id","tracks":[{"track_id":"remote-1","title":"Track",
                "size":7,"format":"flac","checksum":"${"a".repeat(64)}"}],
                "total_tracks":1,"total_size":7}""",HttpStatusCode.OK,
                airCatMetadataHeaders())
        }
        val client=LinkClient.createForTest(baseUrl="https://home.example",deviceToken="secret",
            httpClient=jsonClient(engine),assumePinnedAirCatForTest=true)
        val result=client.fetchAirCatManifest()
        assertTrue(result.isSuccess)
        assertEquals("/api/v1/aircat/manifest",requested)
        assertEquals(7L,result.getOrThrow().effectiveTracks.single().size)
    }

    @Test
    fun airCatManifest_neverAcceptsLegacyHashlessCatalog() = runBlocking {
        val paths=mutableListOf<String>()
        val engine=MockEngine { request ->
            paths+=request.url.encodedPath
            respond("""{"tracks":[{"track_id":"id","title":"legacy"}],"total":1}""",
                HttpStatusCode.OK,airCatMetadataHeaders())
        }
        val client=LinkClient.createForTest(baseUrl="https://home.example",deviceToken="secret",
            httpClient=jsonClient(engine),assumePinnedAirCatForTest=true)
        assertTrue(client.fetchAirCatManifest().isFailure)
        assertEquals(listOf("/api/v1/aircat/manifest"),paths)
    }

    @Test
    fun airCatManifest_rejectsInconsistentTotals() = runBlocking {
        val engine=MockEngine {
            respond("""{"schema":"michi.aircat.manifest.v1","version":1,
                "device_id":"source-id","tracks":[{"track_id":"remote","size":6,
                "format":"flac","checksum":"${"b".repeat(64)}"}],
                "total_tracks":1,"total_size":7}""", HttpStatusCode.OK,
                airCatMetadataHeaders())
        }
        val client=LinkClient.createForTest(baseUrl="https://home.example",deviceToken="secret",
            httpClient=jsonClient(engine),assumePinnedAirCatForTest=true)
        assertTrue(client.fetchAirCatManifest().isFailure)
    }

    @Test
    fun airCatManifest_rejectsImplicitZeroTotalForNonemptyCatalog() = runBlocking {
        val engine = MockEngine {
            respond("""{"schema":"michi.aircat.manifest.v1","version":1,
                "device_id":"source-id","tracks":[{"track_id":"asset-A","size":3,
                "format":"wav","checksum":"${"a".repeat(64)}"}]}""",
                HttpStatusCode.OK,
                airCatMetadataHeaders())
        }
        val client = LinkClient.createForTest(baseUrl="https://home.example",deviceToken="secret",
            httpClient=jsonClient(engine),assumePinnedAirCatForTest=true)
        assertTrue(client.fetchAirCatManifest().isFailure)
        client.close()
    }

    @Test
    fun airCatManifest_rejectsDuplicateLogicalIdsEvenWithUniqueRemoteIds() = runBlocking {
        val engine = MockEngine {
            respond("""{"schema":"michi.aircat.manifest.v1","version":1,
                "device_id":"source-id","total_tracks":2,"total_size":6,
                "tracks":[{"track_id":"asset-A","logical_track_id":"one","size":3,
                  "format":"wav","checksum":"${"a".repeat(64)}"},
                  {"track_id":"asset-B","logical_track_id":"one","size":3,
                  "format":"wav","checksum":"${"b".repeat(64)}"}]}""",
                HttpStatusCode.OK,
                airCatMetadataHeaders())
        }
        val client = LinkClient.createForTest(baseUrl="https://home.example",deviceToken="secret",
            httpClient=jsonClient(engine),assumePinnedAirCatForTest=true)
        assertTrue(client.fetchAirCatManifest().isFailure)
        client.close()
    }

    @Test
    fun airCatManifest_preservesDifferentLogicalAndOriginalAssetIdentifiers() = runBlocking {
        val engine = MockEngine {
            respond("""{"schema":"michi.aircat.manifest.v1","version":1,
                "device_id":"source-id","total_tracks":1,"total_size":3,
                "tracks":[{"track_id":"catalog-A","logical_track_id":"local-A",
                  "remote_track_id":"asset-A","size":3,
                  "format":"wav","checksum":"${"a".repeat(64)}"}]}""",
                HttpStatusCode.OK,
                airCatMetadataHeaders())
        }
        val client = LinkClient.createForTest(baseUrl="https://home.example",deviceToken="secret",
            httpClient=jsonClient(engine),assumePinnedAirCatForTest=true)
        val r = client.fetchAirCatManifest().getOrThrow()
        assertEquals("local-A", r.effectiveTracks.single().effectiveAirCatLogicalId)
        assertEquals("asset-A", r.effectiveTracks.single().effectiveAirCatRemoteId)
        client.close()
    }

    @Test
    fun airCatManifest_doesNotInterpretLegacyDeltaAsOriginalCatalog() = runBlocking {
        val engine = MockEngine {
            respond("""{"schema":"michi.aircat.manifest.v1","version":1,
                "device_id":"source-id","total_tracks":1,"total_size":3,
                "added":[{"track_id":"asset-A","size":3,
                "format":"wav","checksum":"${"a".repeat(64)}"}]}""",
                HttpStatusCode.OK,
                airCatMetadataHeaders())
        }
        val client = LinkClient.createForTest(baseUrl="https://home.example",deviceToken="secret",
            httpClient=jsonClient(engine),assumePinnedAirCatForTest=true)
        assertTrue(client.fetchAirCatManifest().isFailure)
        client.close()
    }

}
