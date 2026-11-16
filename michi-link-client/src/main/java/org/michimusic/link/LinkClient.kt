package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.ByteArrayOutputStream
import okhttp3.CertificatePinner
import java.net.URI
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.michimusic.link.dto.AlbumDetailDto
import org.michimusic.link.dto.AlbumDto
import org.michimusic.link.dto.ArtistDetailDto
import org.michimusic.link.dto.ArtistDto
import org.michimusic.link.dto.BookmarkDto
import org.michimusic.link.dto.BookmarkUpsertRequest
import org.michimusic.link.dto.DeviceRevokeRequest
import org.michimusic.link.dto.HistoryResponse
import org.michimusic.link.dto.LibraryResponseDto
import org.michimusic.link.dto.ListResponse
import org.michimusic.link.dto.ManifestTrackDto
import org.michimusic.link.dto.LegacyPairConfirmResponseDto
import org.michimusic.link.dto.LegacyPairStartResponseDto
import org.michimusic.link.dto.PairConfirmRequestDto
import org.michimusic.link.dto.PairConfirmResponseDto
import org.michimusic.link.dto.PairStartRequestDto
import org.michimusic.link.dto.PairStartResponseDto
import org.michimusic.link.dto.PlaybackControlRequestDto
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.PlaylistCreateRequest
import org.michimusic.link.dto.PlaylistDetailDto
import org.michimusic.link.dto.PlaylistTracksUpdateRequest
import org.michimusic.link.dto.PlaylistUpdateRequest
import org.michimusic.link.dto.QrClaimRequest
import org.michimusic.link.dto.QrClaimResponse
import org.michimusic.link.dto.QrPairingRequest
import org.michimusic.link.dto.QrPairingResponse
import org.michimusic.link.dto.QueueDto
import org.michimusic.link.dto.QueueJumpRequestDto
import org.michimusic.link.dto.QueueRepeatRequestDto
import org.michimusic.link.dto.QueueRepeatResponseDto
import org.michimusic.link.dto.QueueReorderRequest
import org.michimusic.link.dto.QueueSaveRequest
import org.michimusic.link.dto.QueueShuffleRequestDto
import org.michimusic.link.dto.QueueShuffleResponseDto
import org.michimusic.link.dto.QueueTransferRequest
import org.michimusic.link.dto.QueueTransferResponse
import org.michimusic.link.dto.RateRequest
import org.michimusic.link.dto.RateResponse
import org.michimusic.link.dto.ReceiverDto
import org.michimusic.link.dto.ReceiverHeartbeatRequest
import org.michimusic.link.dto.ReceiverHeartbeatResponse
import org.michimusic.link.dto.ReceiverSessionCreateRequest
import org.michimusic.link.dto.ReceiverSessionCreateResponse
import org.michimusic.link.dto.ReceiverSessionPatchRequest
import org.michimusic.link.dto.ReceiverSessionRequest
import org.michimusic.link.dto.ReceiverSessionStateDto
import org.michimusic.link.dto.ReceiverVolumeRequest
import org.michimusic.link.dto.RoomCreateRequest
import org.michimusic.link.dto.RoomDto
import org.michimusic.link.dto.RoomPlayRequest
import org.michimusic.link.dto.SavedQueueDto
import org.michimusic.link.dto.SearchResponseDto
import org.michimusic.link.dto.ServerInfoDto
import org.michimusic.link.dto.StarResponse
import org.michimusic.link.dto.StarredListResponse
import org.michimusic.link.dto.SyncManifestDto
import org.michimusic.link.dto.SyncStateEntry
import org.michimusic.link.dto.TokenRefreshRequestDto
import org.michimusic.link.dto.TokenRefreshResponseDto
import org.michimusic.link.dto.TrackBulkRequest
import org.michimusic.link.dto.TrackBulkResponse
import org.michimusic.link.dto.TrackListResponseDto
import org.michimusic.link.dto.TrackResponseDto
import org.michimusic.link.errors.LinkException
import org.michimusic.link.errors.LinkErrorResponse
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.michimusic.link.port.RemoteFileSource
import org.michimusic.link.port.PinnedOriginalFileSource

class LinkClient(
    baseUrl: String,
    @Volatile var sessionToken: String = "",
    @Volatile var deviceToken: String = "",
    @Volatile var clientDeviceId: String = "",
    @Volatile var pairedClientDeviceId: String = "",
) : RemoteFileSource {
    internal constructor(
        baseUrl: String,
        sessionToken: String,
        deviceToken: String,
        clientDeviceId: String,
        httpClient: HttpClient,
    ) : this(baseUrl, sessionToken, deviceToken, clientDeviceId, "") {
        this.client = httpClient
        this.ownsClient = false
    }

    val baseUrl: String = LinkClientConfig.normalizeBaseUrl(baseUrl)
    @Volatile var tokenRefreshSupported: Boolean? = null
    @Volatile var currentReceiverSessionToken: String? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var ownsClient = true
    private var pinnedAirCatMetadata = false

    // A25: a dedicated HTTP client derived from the same engine/configuration but
    // WITHOUT redirects. No redirect may forward a bearer before validation.
    // Lazy: playback/normal Link control pay zero additional resources.
    private val lazyRawClient = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        client.config { followRedirects = false }
    }
    private val originalBytesHttpClient: HttpClient get() = lazyRawClient.value

    private var client: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    companion object {
        fun createForTest(
            baseUrl: String,
            sessionToken: String = "",
            deviceToken: String = "",
            clientDeviceId: String = "",
            httpClient: HttpClient,
            assumePinnedAirCatForTest: Boolean = false,
        ): LinkClient = LinkClient(baseUrl, sessionToken, deviceToken, clientDeviceId, httpClient).also {
            // Only tests with MockEngine use this seam; production obtains the
            // attestation from pinnedAirCatSession and an actual OkHttp pinner.
            it.pinnedAirCatMetadata = assumePinnedAirCatForTest
        }
    }

    val isAuthenticated: Boolean get() = sessionToken.isNotEmpty() || deviceToken.isNotEmpty()

    fun createEventClient(token: String = deviceToken.ifEmpty { sessionToken }): EventClient {
        return EventClient(baseUrl, token, clientDeviceId, client)
    }

    private fun authHeader(): String {
        return "Bearer ${deviceToken.ifEmpty { sessionToken }}"
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun encodePathSegment(value: String): String =
        encodeQueryValue(value).replace("+", "%20")

    private fun effectiveDeviceId(): String = pairedClientDeviceId.ifEmpty { clientDeviceId }

    private suspend fun httpGet(url: String): HttpResponse = withContext(Dispatchers.IO) {
        client.get(url) {
            if (isAuthenticated) header("Authorization", authHeader())
            val devId = effectiveDeviceId()
            if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
        }
    }

    private fun parseError(body: String): LinkException {
        return try {
            val err = json.decodeFromString<LinkErrorResponse>(body)
            when (err.error?.code) {
                "UNAUTHORIZED" -> LinkException.Unauthorized
                "FORBIDDEN" -> LinkException.Forbidden
                "TOKEN_REVOKED" -> LinkException.Revoked
                "NOT_IMPLEMENTED" -> LinkException.NotImplemented
                "PAIRING_REQUIRED" -> LinkException.PairingRequired
                else -> LinkException.ServerError(err.error?.code ?: "", err.error?.message ?: "")
            }
        } catch (_: Exception) {
            LinkException.ServerError("UNKNOWN", body.take(200))
        }
    }

    private fun HttpStatusCode.checkError(body: String? = null): LinkException? = when {
        this == HttpStatusCode.Unauthorized -> LinkException.Unauthorized
        this == HttpStatusCode.Forbidden -> LinkException.Revoked
        this == HttpStatusCode.Conflict -> LinkException.SessionConflict
        this == HttpStatusCode.NotImplemented -> LinkException.NotImplemented
        this == HttpStatusCode.RequestedRangeNotSatisfiable -> LinkException.RangeNotSatisfiable
        value >= 400 -> body?.let { parseError(it) } ?: LinkException.ServerError("$value", "HTTP $value")
        else -> null
    }

    private suspend inline fun <reified B : Any, reified T> httpPost(
        url: String,
        body: B,
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                if (isAuthenticated) header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(json.encodeToString(body))
            }
            response.status.checkError(response.bodyOrNull())?.let { return@withContext Result.failure(it) }
            Result.success(response.body<T>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun HttpResponse.bodyOrNull(): String? = try { body() } catch (_: Exception) { null }

    /**
     * A37: bound the actual bytes received, not only the advertised Content-Length
     * or the post-allocation String.length. Called only for explicit AirCat HTTP.
     * Reading empty non-closed Ktor channels suspends instead of busy-spinning.
     */
    private suspend fun readAirCatMetadataText(response: HttpResponse, limit: Int): String {
        val lengths = response.headers.getAll("Content-Length")
        require((lengths?.size ?: 0) <= 1 &&
            AirCatMetadataBudget.contentLengthAllowed(response.headers["Content-Length"], limit)) {
            "AIRC_METADATA_CONTENT_LENGTH_REFUSED"
        }
        val budget = AirCatMetadataBudget(limit)
        val body = response.bodyAsChannel()
        val result = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (!body.isClosedForRead) {
            val count = body.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) {
                if (!body.awaitContent(1)) break
                continue
            }
            require(budget.accept(count)) { "AIRC_METADATA_BODY_TOO_LARGE" }
            result.write(buffer, 0, count)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val declared = response.headers["Content-Length"]?.toLongOrNull()
        require(declared == null || declared == budget.consumedBytes.toLong()) {
            "AIRC_METADATA_LENGTH_MISMATCH"
        }
        return String(result.toByteArray(), Charsets.UTF_8)
    }

    /**
     * A27 upstream integration boundary (currently NO-GO until Micro implements
     * this endpoint): called ONLY on pinnedAirCatSession, never on plain HTTP.
     * The returned grant is untrusted until the AirCat Ed25519 verifier checks it.
     */
    suspend fun requestAirCatAuthorization(requestJson: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(requestJson.toByteArray(Charsets.UTF_8).size in 1..262144) { "AIRC_GRANT_REQUEST_SIZE" }
                require(pinnedAirCatMetadata && baseUrl.startsWith("https://", ignoreCase = true)) {
                    "AIRC_TLS_PIN_REQUIRED: unpinned grant request rejected"
                }
                val response = client.post("$baseUrl/api/v1/aircat/authorize") {
                    contentType(ContentType.Application.Json)
                    if (isAuthenticated) header("Authorization", authHeader())
                    val devId = effectiveDeviceId()
                    if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                    setBody(requestJson)
                }
                require(response.call.request.url.toString() == "$baseUrl/api/v1/aircat/authorize") {
                    "AIRC_AUTH_ORIGIN_CHANGED"
                }
                // Never buffer an untrusted error body or success JSON without a limit.
                // 3xx is refused before reading any response body.
                if (response.status.value in 300..399)
                    error("AIRC_AUTH_REDIRECT_REFUSED")
                if (response.status.value in 200..299) {
                    val refusal = AirCatProducerContractPolicy.refusal(
                        AirCatProducerContractPolicy.Phase.AUTHORIZE,
                        response.headers.getAll(AirCatProducerContractPolicy.HEADER),
                        response.headers.getAll("Content-Type"),
                        response.headers.getAll("Content-Encoding"),
                        response.headers.getAll("Cache-Control"))
                    require(refusal == null) { refusal ?: "AIRC_PRODUCER_PROFILE" }
                }
                val body = readAirCatMetadataText(response,
                    if (response.status.value in 200..299)
                        AirCatMetadataBudget.AUTHORIZATION_MAX_BYTES
                    else AirCatMetadataBudget.ERROR_MAX_BYTES)
                if (response.status.value !in 200..299) {
                    throw response.status.checkError(body)
                        ?: IllegalStateException("AIRC_AUTH_HTTP_${response.status.value}")
                }
                require(body.length >= 2) { "AIRC_GRANT_RESPONSE_SIZE" }
                body
            }
        }

    // --- Server Info / Ping ---

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/api/v1/status")
            true
        } catch (_: Exception) {
            try {
                client.get("$baseUrl/api/ping")
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun getServerInfo(): Result<ServerInfoDto> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/v1/server/info")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            val info = response.body<ServerInfoDto>()
            updateRefreshSupport(info)
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun updateRefreshSupport(info: ServerInfoDto) {
        tokenRefreshSupported = info.effectiveTokenRefresh
    }

    suspend fun getServerInfoWithFallback(): Result<ServerInfoDto> = withContext(Dispatchers.IO) {
        getServerInfo().recoverCatching {
            try {
                client.get("$baseUrl/api/discovery/info").body<ServerInfoDto>()
            } catch (e2: Exception) {
                throw it
            }
        }
    }

    // --- Pairing ---

    suspend fun pairStartLegacy(
        alias: String,
        deviceModel: String,
        clientDeviceId: String,
    ): Result<LegacyPairStartResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = mapOf(
                "alias" to alias,
                "device_model" to deviceModel,
                "client_device_id" to clientDeviceId
            )
            val response = client.post("$baseUrl/api/v1/pair/start") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            response.status.checkError(response.body())?.let { return@withContext Result.failure(it) }
            // Legacy uses PairStartResponseDto but we changed the DTO structure for v1. 
            // We should use a Map or JsonElement to parse legacy.
            Result.success(response.body<LegacyPairStartResponseDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pairStart(
        request: PairStartRequestDto
    ): Result<PairStartResponseDto> = withContext(Dispatchers.IO) {
        try {
            val response = client.post("$baseUrl/api/v1/pair/start") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            response.status.checkError(response.body())?.let { return@withContext Result.failure(it) }
            Result.success(response.body<PairStartResponseDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pairConfirmLegacy(
        pairingId: String,
        username: String,
        password: String,
        clientDeviceId: String,
        alias: String = "",
        deviceModel: String = "",
        pin: String = "",
    ): Result<LegacyPairConfirmResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = mapOf(
                "pairing_id" to pairingId,
                "username" to username,
                "password" to password,
                "client_device_id" to clientDeviceId,
                "alias" to alias,
                "device_model" to deviceModel,
                "pin" to pin
            )
            val response = client.post("$baseUrl/api/v1/pair/confirm") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.InvalidCredentials)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Forbidden)
            response.status.checkError(response.body())?.let { return@withContext Result.failure(it) }
            val body = response.body<LegacyPairConfirmResponseDto>()
            deviceToken = body.deviceToken.ifEmpty { body.sessionToken }
            if (deviceToken.isBlank()) return@withContext Result.failure(LinkException.InvalidCredentials)
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pairConfirm(
        request: PairConfirmRequestDto
    ): Result<PairConfirmResponseDto> = withContext(Dispatchers.IO) {
        try {
            val response = client.post("$baseUrl/api/v1/pair/confirm") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.InvalidCredentials)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Forbidden)
            response.status.checkError(response.body())?.let { return@withContext Result.failure(it) }
            val body = response.body<PairConfirmResponseDto>()
            deviceToken = body.token
            if (deviceToken.isBlank()) return@withContext Result.failure(LinkException.InvalidCredentials)
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Token Refresh ---

    suspend fun refreshToken(refreshToken: String, force: Boolean = false): Result<TokenRefreshResponseDto> = withContext(Dispatchers.IO) {
        if (!force && tokenRefreshSupported == false) {
            return@withContext Result.failure(LinkException.NotImplemented)
        }
        try {
            val request = TokenRefreshRequestDto(
                refreshToken = refreshToken,
            )
            var response = client.post("$baseUrl/api/v1/token/refresh") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.NotFound) {
                // Fallback to /api/v1/auth/refresh if /token/refresh is 404
                response = client.post("$baseUrl/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }
            if (response.status == HttpStatusCode.NotImplemented) {
                tokenRefreshSupported = false
                return@withContext Result.failure(LinkException.NotImplemented)
            }
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.TokenExpired)
            val body = response.body<TokenRefreshResponseDto>()
            if (body.effectiveToken.isNotEmpty()) {
                deviceToken = body.effectiveToken
            }
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Library ---

    suspend fun getLibraryStats(): Result<org.michimusic.link.dto.LibraryStatsDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/library/stats")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchLibrary(): Result<LibraryResponseDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/tracks")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            val body = response.body<TrackListResponseDto>()
            Result.success(LibraryResponseDto(
                tracks = body.effectiveTracks.map { it.normalized() },
                total = body.effectiveTotal,
            ))
        } catch (e: Exception) {
            try {
                val response = httpGet("$baseUrl/api/library")
                response.status.checkError()?.let { return@withContext Result.failure(it) }
                val body = response.body<LibraryResponseDto>()
                Result.success(body.copy(tracks = body.tracks.map { it.normalized() }))
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    // --- Search ---

    suspend fun search(query: String): Result<List<org.michimusic.link.dto.TrackResponseDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/search?q=${encodeQueryValue(query)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<SearchResponseDto>().results.map { it.normalized() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Albums ---

    suspend fun fetchAlbums(page: Int = 1, pageSize: Int = 50): Result<ListResponse<AlbumDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/albums?page=$page&pageSize=$pageSize")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAlbum(id: String): Result<AlbumDetailDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/albums/${encodePathSegment(id)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Artists ---

    suspend fun fetchArtists(page: Int = 1, pageSize: Int = 50): Result<ListResponse<ArtistDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/artists?page=$page&pageSize=$pageSize")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchArtist(id: String): Result<ArtistDetailDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/artists/${encodePathSegment(id)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Tracks detail ---

    suspend fun fetchTrack(id: String): Result<TrackResponseDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/tracks/${encodePathSegment(id)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<TrackResponseDto>().normalized())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchTracksBulk(ids: List<String>): Result<TrackBulkResponse> =
        httpPost("$baseUrl/api/v1/tracks/bulk", TrackBulkRequest(trackIds = ids))

    // --- Playlists ---

    suspend fun fetchPlaylists(page: Int = 1): Result<ListResponse<PlaylistDetailDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/playlists?page=$page")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPlaylist(request: PlaylistCreateRequest): Result<PlaylistDetailDto> =
        httpPost("$baseUrl/api/v1/playlists", request)

    suspend fun fetchPlaylist(id: String): Result<PlaylistDetailDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/playlists/${encodePathSegment(id)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePlaylist(id: String, request: PlaylistUpdateRequest): Result<PlaylistDetailDto> =
        httpPost("$baseUrl/api/v1/playlists/${encodePathSegment(id)}", request)

    suspend fun deletePlaylist(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.delete("$baseUrl/api/v1/playlists/${encodePathSegment(id)}") {
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchPlaylistTracks(id: String): Result<List<TrackResponseDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/playlists/${encodePathSegment(id)}/tracks")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<TrackListResponseDto>().effectiveTracks.map { it.normalized() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePlaylistTracks(id: String, request: PlaylistTracksUpdateRequest): Result<PlaylistDetailDto> =
        httpPost("$baseUrl/api/v1/playlists/${encodePathSegment(id)}/tracks", request)

    // --- Streaming ---

    suspend fun streamTrack(
        trackId: String,
        outputStream: OutputStream,
        startBytes: Long = 0,
        bufferSize: Int = 65536,
    ): Result<Long> = withContext(Dispatchers.IO) {
        streamTrackRange(
            trackId = trackId,
            outputStream = outputStream,
            startBytes = startBytes,
            bufferSize = bufferSize,
        ).map { it.bytesRead }
    }

    override suspend fun streamTrackRange(
        trackId: String,
        outputStream: OutputStream,
        startBytes: Long,
        expectedEtag: String?,
        bufferSize: Int,
        onProgress: ((bytesRead: Long, totalExpected: Long?) -> Unit)?,
    ): Result<StreamRangeResult> = readRangeFromRoute(
        "stream", trackId, outputStream, startBytes, expectedEtag, bufferSize, onProgress,
    )

    /**
     * A24: managed AirCat must receive source-file bytes, NOT the listening
     * representation produced by /stream (which Micro may transcode).
     * /download is an existing Micro endpoint. No fallback to /stream, even on
     * 404/501: absence of a raw provider is a blocking capability mismatch.
     * This port reuses this same client's token/HTTP engine; it provides no
     * additional encryption, identity or consent by itself.
     */
    fun originalFileSource(): RemoteFileSource = object : RemoteFileSource {
        override val servesOriginalBytes: Boolean get() = true

        override suspend fun streamTrackRange(
            trackId: String,
            outputStream: OutputStream,
            startBytes: Long,
            expectedEtag: String?,
            bufferSize: Int,
            onProgress: ((bytesRead: Long, totalExpected: Long?) -> Unit)?,
        ): Result<StreamRangeResult> = readRangeFromRoute(
            "download", trackId, outputStream, startBytes, expectedEtag, bufferSize, onProgress,
        )
    }

    /**
     * A27: the only managed AirCat raw source. OkHttp enforces CA validation,
     * hostname verification and an independently Home-enrolled SPKI pin. Neither
     * UDP discovery nor /server/info may supply the pin. The grant is forwarded
     * to the producer to be verified on EVERY HTTP request. A server that ignores
     * the grant is not an authorized AirCat producer and must fail server-side.
     * The source owns its HttpClient; callers MUST close after each file.
     */
    /**
     * Pins the metadata phase too: /server/info, manifest and Home grant fetching
     * must never use the regular LAN HTTP client carrying an AirCat bearer.
     * This new client owns its connections; the caller MUST close it.
     */
    fun pinnedAirCatSession(pinnedSpkiSha256: String): LinkClient {
        require(AirCatTlsPinPolicy.refusal(baseUrl, pinnedSpkiSha256) == null) {
            "AIRC_TLS_PIN_REQUIRED"
        }
        val secureHttp = createPinnedHttpsEngine(pinnedSpkiSha256)
        val session = LinkClient(baseUrl, sessionToken, deviceToken, clientDeviceId,
            pairedClientDeviceId)
        session.client.close()  // discard unused default engine, never used for a request
        session.client = secureHttp
        session.ownsClient = true
        session.pinnedAirCatMetadata = true
        session.tokenRefreshSupported = tokenRefreshSupported
        return session
    }

    private fun createPinnedHttpsEngine(pinnedSpkiSha256: String): HttpClient {
        val hostname = URI(baseUrl).host
        val pinner = CertificatePinner.Builder().add(hostname, pinnedSpkiSha256).build()
        return HttpClient(OkHttp) {
            followRedirects = false
            engine {
                config {
                    certificatePinner(pinner)
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            install(ContentNegotiation) { json(json) }
        }
    }

    fun pinnedOriginalFileSource(
        pinnedSpkiSha256: String,
        signedGrantHeader: String,
    ): PinnedOriginalFileSource {
        require(AirCatTlsPinPolicy.refusal(baseUrl, pinnedSpkiSha256) == null) {
            "AIRC_TLS_PIN_REQUIRED: Home-enrolled HTTPS endpoint and pin mandatory"
        }
        // A46: reuse one ASCII/canonical Base64url policy for both the source
        // constructor and producer receipt; no duplicate Unicode predicates.
        require(AirCatProducerReceiptPolicy.validGrantHeader(signedGrantHeader)) {
            "AIRC_GRANT_HEADER_INVALID"
        }
        val pinnedHttp = createPinnedHttpsEngine(pinnedSpkiSha256)
        return object : PinnedOriginalFileSource {
            override val servesOriginalBytes: Boolean = true
            override val verifiedTransport: Boolean = true
            override suspend fun streamTrackRange(
                trackId: String,
                outputStream: OutputStream,
                startBytes: Long,
                expectedEtag: String?,
                bufferSize: Int,
                onProgress: ((bytesRead: Long, totalExpected: Long?) -> Unit)?,
            ): Result<StreamRangeResult> = readRangeFromRoute(
                "aircat/download", trackId, outputStream, startBytes, expectedEtag,
                bufferSize, onProgress, pinnedHttp, signedGrantHeader,
            )
            override fun close() = pinnedHttp.close()
        }
    }

    private suspend fun readRangeFromRoute(
        route: String,
        trackId: String,
        outputStream: OutputStream,
        startBytes: Long,
        expectedEtag: String?,
        bufferSize: Int,
        onProgress: ((bytesRead: Long, totalExpected: Long?) -> Unit)?,
        forcedHttpClient: HttpClient? = null,
        signedGrantHeader: String? = null,
    ): Result<StreamRangeResult> = withContext(Dispatchers.IO) {
        try {
            require(route == "stream" || route == "download" || route == "aircat/download") {
                "Unsupported Link file route"
            }
            require(route != "aircat/download" || signedGrantHeader != null) {
                "AIRC_RAW_GRANT_REQUIRED"
            }
            require(trackId.isNotBlank()) { "Empty remote track identifier" }
            // A30: malicious/inadvertent zero or oversized AirCat read buffers
            // cannot turn a stalled network channel into a CPU spin or RAM spike.
            if (route == "aircat/download") {
                require(AirCatReadBufferPolicy.refusal(bufferSize) == null) {
                    "AIRC_INVALID_READ_BUFFER"
                }
            }
            val requestUrl = "$baseUrl/api/v1/$route/${encodePathSegment(trackId)}"
            val response = (forcedHttpClient ?: if (route != "stream") originalBytesHttpClient else client).get(requestUrl) {
                if (signedGrantHeader != null) header(AirCatProducerReceiptPolicy.REQUEST_HEADER, signedGrantHeader)
                // A37: request source bytes as an identity representation. If
                // the producer compresses anyway, reject it before writing.
                if (route == "aircat/download") header("Accept-Encoding", "identity")
                if (isAuthenticated) header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                if (startBytes > 0) header("Range", "bytes=$startBytes-")
                if (startBytes > 0 && !expectedEtag.isNullOrBlank()) header("If-Range", expectedEtag)
            }
            // With redirect following disabled, 3xx is rejected before any
            // second request. The equality check is defense in depth for custom
            // client engines that rewrite the request URL.
            if (route != "stream" && response.call.request.url.toString() != requestUrl) {
                return@withContext Result.failure(IllegalStateException(
                    "AIRC_RAW_REDIRECT_REFUSED: original source changed URL"))
            }
            if (route != "stream" && response.status.value in 300..399) {
                return@withContext Result.failure(IllegalStateException(
                    "AIRC_RAW_REDIRECT_REFUSED: raw downloads cannot redirect"))
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            if (route == "aircat/download") {
                val contractRefusal = AirCatProducerContractPolicy.refusal(
                    AirCatProducerContractPolicy.Phase.ORIGINAL,
                    response.headers.getAll(AirCatProducerContractPolicy.HEADER),
                    response.headers.getAll("Content-Type"),
                    response.headers.getAll("Content-Encoding"),
                    response.headers.getAll("Cache-Control"))
                if (contractRefusal != null) return@withContext Result.failure(
                    IllegalStateException(contractRefusal))
            }
            // Reject legacy raw endpoints that simply ignore the signed grant.
            // Header must be emitted ONLY after the producer has validated the
            // issuer, scope, source/destination, nonce and exact asset ACL.
            val producerReceipts = response.headers.getAll(AirCatProducerReceiptPolicy.RESPONSE_HEADER)
            // A46: two acknowledgment headers with conflicting values are
            // ambiguous even when one matches. Deny before opening the body.
            if (signedGrantHeader != null && (producerReceipts?.size != 1 ||
                    !AirCatProducerReceiptPolicy.recognizesAuthorization(
                        signedGrantHeader, producerReceipts.single()))) {
                return@withContext Result.failure(IllegalStateException(
                    "AIRC_PRODUCER_AUTHORIZATION_NOT_CONFIRMED"))
            }

            // ETag is an opaque HTTP entity tag: preserve its quotes and W/ prefix.
            val responseEtag = response.headers["ETag"].orEmpty()
            val isPartial = response.status == HttpStatusCode.PartialContent
            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            val contentRange = response.headers["Content-Range"]
            if (route == "aircat/download") {
                // Duplicated/fractured validators are ambiguous. Absent and
                // weak ETags are forbidden for authenticated original assets.
                val etags = response.headers.getAll("ETag")
                val lengths = response.headers.getAll("Content-Length")
                val ranges = response.headers.getAll("Content-Range")
                if (etags?.size != 1 || (lengths?.size ?: 0) > 1 ||
                    (ranges?.size ?: 0) > 1 ||
                    (response.headers["Content-Length"] != null && contentLength == null)) {
                    return@withContext Result.failure(IllegalStateException(
                        "AIRC_HTTP_AMBIGUOUS_HEADERS"))
                }
                val verdict = AirCatOriginalHttpPolicy.evaluate(
                    requestedOffset = startBytes, statusCode = response.status.value,
                    contentRange = contentRange, contentLength = contentLength,
                    contentEncoding = response.headers["Content-Encoding"],
                    etag = responseEtag, expectedEtag = expectedEtag,
                )
                if (!verdict.allowed) return@withContext Result.failure(
                    IllegalStateException(verdict.refusal ?: "AIRC_HTTP_REFUSED"))
            }
            val error = validateStreamRangeHeaders(startBytes, response.status.value,
                contentRange, contentLength, expectedEtag, responseEtag)
            if (error != null) return@withContext Result.failure(IllegalStateException("INVALID_RANGE_RESPONSE: $error"))

            // A server may ignore Range and reply 200. Do not append its full
            // body to a .part: the transfer manager will restart from zero.
            if (startBytes > 0 && !isPartial) {
                return@withContext Result.success(StreamRangeResult(
                    bytesRead = 0L, statusCode = response.status.value,
                    isPartialContent = false, etag = responseEtag,
                    contentLength = contentLength, contentRange = contentRange,
                ))
            }

            val channel = response.bodyAsChannel()
            var totalRead = 0L
            val buffer = ByteArray(bufferSize)
            while (isActive && !channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) {
                    // Ktor ByteReadChannel.awaitContent suspends; do not busy-spin
                    // on an empty channel while Wi-Fi stalls. No worker or timer.
                    if (!channel.awaitContent(1)) break
                    continue
                }
                outputStream.write(buffer, 0, read)
                totalRead += read
                onProgress?.invoke(totalRead, contentLength)
            }
            if (contentLength != null && totalRead != contentLength) {
                return@withContext Result.failure(IllegalStateException("TRUNCATED_STREAM: received $totalRead of $contentLength bytes"))
            }
            if (isPartial && startBytes > 0L) {
                // Header format was validated before streaming; still ensure
                // the promised range bytes actually reached the .part file.
                val end = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
                    .matchEntire(contentRange.orEmpty())!!.groupValues[2].toLong()
                if (totalRead != end - startBytes + 1L) {
                    return@withContext Result.failure(IllegalStateException("TRUNCATED_RANGE: advertised range not fully received"))
                }
            }
            // Never report a successful partial stream when coroutine cancellation
            // stopped the read loop (including responses without Content-Length).
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            outputStream.flush()
            Result.success(
                StreamRangeResult(
                    bytesRead = totalRead,
                    statusCode = response.status.value,
                    isPartialContent = isPartial,
                    etag = responseEtag,
                    contentLength = contentLength,
                    contentRange = contentRange,
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadTrack(trackId: String, outputStream: OutputStream): Result<Long> =
        streamTrack(trackId, outputStream)

    suspend fun fetchCover(
        coverId: String,
        outputStream: OutputStream,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/artwork/${encodePathSegment(coverId)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            val channel = response.bodyAsChannel()
            var totalRead = 0L
            val buffer = ByteArray(8192)
            while (isActive && !channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) {
                    // Ktor ByteReadChannel.awaitContent suspends; do not busy-spin
                    // on an empty channel while Wi-Fi stalls. No worker or timer.
                    if (!channel.awaitContent(1)) break
                    continue
                }
                outputStream.write(buffer, 0, read)
                totalRead += read
            }
            outputStream.flush()
            Result.success(totalRead)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Sync ---

    /**
     * A28: managed AirCat does NOT use the legacy sync manifest, because Micro
     * does not supply original file size/sha256 in that endpoint. This producer
     * API is new and must be served over the pinned HTTPS metadata channel.
     * There is intentionally no fallback to the unverified sync manifest.
     */
    suspend fun fetchAirCatManifest(): Result<SyncManifestDto> = withContext(Dispatchers.IO) {
        runCatching {
            require(pinnedAirCatMetadata && baseUrl.startsWith("https://", ignoreCase = true)) {
                "AIRC_PINNED_MANIFEST_REQUIRED"
            }
            val response = client.get("$baseUrl/api/v1/aircat/manifest") {
                if (isAuthenticated) header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
            }
            require(response.status.value in 200..299 &&
                response.call.request.url.toString() == "$baseUrl/api/v1/aircat/manifest") {
                "AIRC_MANIFEST_ORIGIN_OR_STATUS_REFUSED_${response.status.value}"
            }
            val contractRefusal = AirCatProducerContractPolicy.refusal(
                AirCatProducerContractPolicy.Phase.MANIFEST,
                response.headers.getAll(AirCatProducerContractPolicy.HEADER),
                response.headers.getAll("Content-Type"),
                response.headers.getAll("Content-Encoding"),
                response.headers.getAll("Cache-Control"))
            require(contractRefusal == null) { contractRefusal ?: "AIRC_PRODUCER_PROFILE" }
            // Must be a dedicated original-file manifest. Legacy fallback is not
            // authorized and can never be counted as SHA-verified AirCat content.
            // A37: streaming cap also covers chunked responses with no size header.
            val value = json.decodeFromString<SyncManifestDto>(readAirCatMetadataText(
                response, AirCatMetadataBudget.MANIFEST_MAX_BYTES))
            require(value.schema == "michi.aircat.manifest.v1" && value.version == 1) {
                "AIRC_MANIFEST_SCHEMA_REQUIRED"
            }
            // A29: a dedicated AirCat manifest is a FULL immutable original-file
            // catalog. Never interpret legacy delta fields as a transfer offer.
            require(value.added.isEmpty() && value.updated.isEmpty() &&
                value.modified.isEmpty() && value.deleted.isEmpty() && value.removed.isEmpty()) {
                "AIRC_MANIFEST_LEGACY_DELTA_REFUSED"
            }
            val tracks = value.tracks
            require(tracks.size <= 10_000 && value.deviceId.isNotBlank() &&
                tracks.map { it.effectiveAirCatRemoteId }.distinct().size == tracks.size &&
                tracks.map { it.effectiveAirCatLogicalId }.distinct().size == tracks.size) {
                "AIRC_MANIFEST_TRACK_ID_INVALID"
            }
            var expectedBytes = 0L
            for (track in tracks) {
                require(track.trackId.isNotBlank() &&
                    track.effectiveAirCatRemoteId.isNotBlank() &&
                    track.effectiveAirCatLogicalId.isNotBlank() &&
                    track.size > 0L && track.format.isNotBlank() &&
                    track.checksum?.matches(Regex("[a-fA-F0-9]{64}")) == true) {
                    "AIRC_MANIFEST_ORIGINAL_SHA_REQUIRED"
                }
                expectedBytes = Math.addExact(expectedBytes, track.size)
            }
            // A29: legacy SyncManifestDto defaults are NOT valid signed AirCat
            // totals. Require them explicitly, including an honest 0/0 empty catalog.
            require(value.totalTracks == tracks.size && value.totalSize == expectedBytes) {
                "AIRC_MANIFEST_TOTAL_MISMATCH"
            }
            value
        }
    }

    suspend fun fetchSyncManifest(deviceId: String): Result<SyncManifestDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/sync/manifest?device_id=${encodeQueryValue(deviceId)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<SyncManifestDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSyncManifestDelta(deviceId: String, cursor: String): Result<SyncManifestDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet(
                "$baseUrl/api/v1/sync/manifest/delta?device_id=${encodeQueryValue(deviceId)}&cursor=${encodeQueryValue(cursor)}",
            )
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<SyncManifestDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncState(entries: List<SyncStateEntry>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf("tracks" to entries.map { e ->
                mapOf("track_id" to e.trackId, "play_count" to e.playCount, "favorite" to e.favorite)
            })
            val response = client.post("$baseUrl/api/v1/sync/state") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(json.encodeToString(body))
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            val result = json.decodeFromString<Map<String, Int>>(response.body())
            Result.success(result["synced"] ?: 0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Remote Playback ---

    suspend fun getPlaybackState(): Result<PlaybackStateDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/playback/state")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<PlaybackStateDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPlaybackCommand(command: String, value: String = ""): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = PlaybackControlRequestDto(command = command, value = value)
            val response = client.post("$baseUrl/api/v1/playback/control") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendSeek(positionMs: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = PlaybackControlRequestDto(
                command = "seek",
                positionMs = positionMs,
            )
            val response = client.post("$baseUrl/api/v1/playback/control") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendSetVolume(volume: Int): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = PlaybackControlRequestDto(
                command = "set_volume",
                volume = volume.coerceIn(0, 100),
            )
            val response = client.post("$baseUrl/api/v1/playback/control") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Queue ---

    suspend fun getQueue(): Result<QueueDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/queue")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            val raw = response.body<String>()
            val parsed = json.decodeFromString<QueueDto>(raw)
            // If empty and server uses items/current_index, try alternate parsing
            if (parsed.tracks.isEmpty() && raw.contains("items")) {
                val alt = json.decodeFromString<AltQueueDto>(raw)
                Result.success(QueueDto(
                    tracks = alt.items,
                    currentIndex = alt.effectiveIndex,
                    queueId = alt.effectiveQueueId,
                    totalDuration = alt.effectiveTotalDuration,
                ))
            } else {
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun queueJump(index: Int): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = QueueJumpRequestDto(index = index)
            val response = client.post("$baseUrl/api/v1/queue/jump") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setQueueRepeatMode(mode: String): Result<QueueRepeatResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = QueueRepeatRequestDto(mode = mode)
            val response = client.post("$baseUrl/api/v1/queue/repeat") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setQueueShuffle(enabled: Boolean): Result<QueueShuffleResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = QueueShuffleRequestDto(enabled = enabled)
            val response = client.post("$baseUrl/api/v1/queue/shuffle") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Queue (extended) ---

    suspend fun reorderQueue(request: QueueReorderRequest): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.put("$baseUrl/api/v1/queue/reorder") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeQueueItem(queueItemId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.delete("$baseUrl/api/v1/queue/items/${encodePathSegment(queueItemId)}") {
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveQueue(request: QueueSaveRequest): Result<QueueDto> =
        httpPost("$baseUrl/api/v1/queue/save", request)

    suspend fun fetchSavedQueues(): Result<List<SavedQueueDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/queue/saved")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun transferQueue(request: QueueTransferRequest): Result<QueueTransferResponse> =
        httpPost("$baseUrl/api/v1/queue/transfer", request)

    // --- Favorites, Rating, History, Bookmarks ---

    suspend fun fetchStarred(page: Int = 1): Result<StarredListResponse> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/starred?page=$page")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun starTrack(id: String): Result<StarResponse> =
        httpPost("$baseUrl/api/v1/star/${encodePathSegment(id)}", emptyMap<String, String>())

    suspend fun unstarTrack(id: String): Result<StarResponse> = withContext(Dispatchers.IO) {
        try {
            val response = client.delete("$baseUrl/api/v1/star/${encodePathSegment(id)}") {
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<StarResponse>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rateTrack(id: String, rating: Int): Result<RateResponse> =
        httpPost("$baseUrl/api/v1/rate/${encodePathSegment(id)}", RateRequest(rating = rating))

    suspend fun fetchHistory(page: Int = 1): Result<HistoryResponse> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/history?page=$page")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearHistory(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.delete("$baseUrl/api/v1/history") {
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchBookmarks(): Result<List<BookmarkDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/bookmarks")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            val body = response.body<Map<String, List<BookmarkDto>>>()
            Result.success(body["bookmarks"] ?: body["items"] ?: emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertBookmark(trackId: String, request: BookmarkUpsertRequest): Result<BookmarkDto> =
        httpPost("$baseUrl/api/v1/bookmarks/${encodePathSegment(trackId)}", request)

    suspend fun deleteBookmark(trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.delete("$baseUrl/api/v1/bookmarks/${encodePathSegment(trackId)}") {
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Receivers ---

    suspend fun fetchReceivers(): Result<List<ReceiverDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/receivers")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchReceiver(id: String): Result<ReceiverDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/receivers/${encodePathSegment(id)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startReceiverSession(id: String, request: ReceiverSessionRequest = ReceiverSessionRequest()): Result<Unit> =
        httpPost("$baseUrl/api/v1/receivers/${encodePathSegment(id)}/session/start", request)

    suspend fun stopReceiverSession(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.post("$baseUrl/api/v1/receivers/${encodePathSegment(id)}/session/stop") {
                header("Authorization", authHeader())
                val devId = effectiveDeviceId()
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setReceiverVolume(id: String, volume: Int): Result<Unit> =
        httpPost("$baseUrl/api/v1/receivers/${encodePathSegment(id)}/volume", ReceiverVolumeRequest(volume = volume))

    // --- Canonical Receiver Lite (v1-lite) ---

    suspend fun createReceiverLiteSession(
        request: ReceiverSessionCreateRequest = ReceiverSessionCreateRequest()
    ): Result<ReceiverSessionCreateResponse> = withContext(Dispatchers.IO) {
        try {
            val response = client.post("$baseUrl/api/v1/receiver-lite/session") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = pairedClientDeviceId.ifEmpty { clientDeviceId }
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                setBody(request)
            }
            response.status.checkError(response.body())?.let { return@withContext Result.failure(it) }
            val resp: ReceiverSessionCreateResponse = response.body()
            currentReceiverSessionToken = resp.sessionToken
            Result.success(resp)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getReceiverLiteSession(
        sessionToken: String? = currentReceiverSessionToken
    ): Result<ReceiverSessionStateDto> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/v1/receiver-lite/session") {
                header("Authorization", authHeader())
                val devId = pairedClientDeviceId.ifEmpty { clientDeviceId }
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                val sTok = sessionToken ?: currentReceiverSessionToken
                if (!sTok.isNullOrEmpty()) header("X-Michi-Session", sTok)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun patchReceiverLiteSession(
        request: ReceiverSessionPatchRequest,
        sessionToken: String? = currentReceiverSessionToken
    ): Result<ReceiverSessionStateDto> = withContext(Dispatchers.IO) {
        try {
            val response = client.patch("$baseUrl/api/v1/receiver-lite/session") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = pairedClientDeviceId.ifEmpty { clientDeviceId }
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                val sTok = sessionToken ?: currentReceiverSessionToken
                if (!sTok.isNullOrEmpty()) header("X-Michi-Session", sTok)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteReceiverLiteSession(
        sessionToken: String? = currentReceiverSessionToken
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.delete("$baseUrl/api/v1/receiver-lite/session") {
                header("Authorization", authHeader())
                val devId = pairedClientDeviceId.ifEmpty { clientDeviceId }
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                val sTok = sessionToken ?: currentReceiverSessionToken
                if (!sTok.isNullOrEmpty()) header("X-Michi-Session", sTok)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            currentReceiverSessionToken = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendReceiverLiteHeartbeat(
        sessionId: String,
        sequence: Long,
        sessionToken: String? = currentReceiverSessionToken
    ): Result<ReceiverHeartbeatResponse> = withContext(Dispatchers.IO) {
        try {
            val request = ReceiverHeartbeatRequest(
                sessionId = sessionId,
                sequence = sequence,
                sentAtMs = System.currentTimeMillis(),
            )
            val response = client.post("$baseUrl/api/v1/receiver-lite/heartbeat") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                val devId = pairedClientDeviceId.ifEmpty { clientDeviceId }
                if (devId.isNotEmpty()) header("X-Michi-Device-Id", devId)
                val sTok = sessionToken ?: currentReceiverSessionToken
                if (!sTok.isNullOrEmpty()) header("X-Michi-Session", sTok)
                setBody(request)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Rooms ---

    suspend fun fetchRooms(): Result<List<RoomDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/rooms")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createRoom(request: RoomCreateRequest): Result<RoomDto> =
        httpPost("$baseUrl/api/v1/rooms", request)

    suspend fun fetchRoom(id: String): Result<RoomDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/rooms/${encodePathSegment(id)}")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun playInRoom(id: String, request: RoomPlayRequest): Result<Unit> =
        httpPost("$baseUrl/api/v1/rooms/${encodePathSegment(id)}/play", request)

    // --- QR Pairing ---

    suspend fun pairQr(request: QrPairingRequest): Result<QrPairingResponse> =
        httpPost("$baseUrl/api/v1/pair/qr", request)

    suspend fun claimQr(qrCode: String, request: QrClaimRequest): Result<QrClaimResponse> =
        httpPost("$baseUrl/api/v1/pair/qr/${encodePathSegment(qrCode)}/claim", request)

    suspend fun revokeDevice(request: DeviceRevokeRequest): Result<Unit> =
        httpPost("$baseUrl/api/v1/devices/revoke", request)

    // --- Diagnostics ---

    suspend fun fetchServerDiagnostics(): Result<Map<String, JsonElement>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/diagnostics")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Legacy Register ---

    suspend fun register(
        alias: String,
        deviceModel: String = "",
        clientDeviceId: String = "",
    ): Result<LegacyPairConfirmResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = org.michimusic.core.models.RegisterRequest(
                alias = alias,
                device = "android",
                deviceModel = deviceModel,
                clientDeviceId = clientDeviceId,
            )
            val response = client.post("$baseUrl/api/register") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Forbidden)
            val legacy = response.body<org.michimusic.core.models.RegisterResponse>()
            sessionToken = legacy.sessionToken
            Result.success(LegacyPairConfirmResponseDto(deviceId = legacy.clientDeviceId, sessionToken = legacy.sessionToken, deviceToken = legacy.sessionToken))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        // Close only if it was instantiated; never allocate a network client
        // simply to close a LinkClient that did no AirCat transfer.
        if (lazyRawClient.isInitialized()) lazyRawClient.value.close()
        if (ownsClient) client.close()
    }
}

@kotlinx.serialization.Serializable
internal data class AltQueueDto(
    val items: List<org.michimusic.link.dto.QueueTrackDto> = emptyList(),
    @kotlinx.serialization.SerialName("currentIndex") val camelCurrentIndex: Int? = null,
    @kotlinx.serialization.SerialName("current_index") val snakeCurrentIndex: Int? = null,
    @kotlinx.serialization.SerialName("queueId") val camelQueueId: String? = null,
    @kotlinx.serialization.SerialName("queue_id") val snakeQueueId: String? = null,
    @kotlinx.serialization.SerialName("totalDuration") val camelTotalDuration: Long? = null,
    @kotlinx.serialization.SerialName("total_duration") val snakeTotalDuration: Long? = null,
) {
    val effectiveIndex: Int get() = snakeCurrentIndex ?: camelCurrentIndex ?: -1
    val effectiveQueueId: String get() = snakeQueueId ?: camelQueueId ?: ""
    val effectiveTotalDuration: Long get() = snakeTotalDuration ?: camelTotalDuration ?: 0L
}

data class StreamRangeResult(
    val bytesRead: Long,
    val statusCode: Int,
    val isPartialContent: Boolean,
    val etag: String = "",
    val contentLength: Long? = null,
    val contentRange: String? = null,
)

/** Validates HTTP Range/ETag response *before* opening the response body. */
internal fun validateStreamRangeHeaders(
    requestedOffset: Long,
    statusCode: Int,
    contentRange: String?,
    contentLength: Long?,
    expectedEtag: String?,
    responseEtag: String,
): String? {
    if (requestedOffset < 0L) return "negative requested offset"
    if (statusCode != 200 && statusCode != 206) return "unexpected status $statusCode"
    if (contentLength != null && contentLength < 0L) return "negative Content-Length"
    if (requestedOffset == 0L) {
        if (statusCode == 206) return "unsolicited partial content"
        return null
    }
    if (statusCode == 200) return null // Caller must restart, never append.
    val match = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
        .matchEntire(contentRange?.trim().orEmpty()) ?: return "missing or malformed Content-Range"
    val first = match.groupValues[1].toLongOrNull() ?: return "invalid range start"
    val last = match.groupValues[2].toLongOrNull() ?: return "invalid range end"
    if (first != requestedOffset || last < first || last == Long.MAX_VALUE) return "wrong range offset/end"
    val totalText = match.groupValues[3]
    if (totalText != "*") {
        val total = totalText.toLongOrNull() ?: return "invalid range total"
        if (total <= last) return "impossible range total"
    }
    val expectedLength = last - first + 1L
    if (contentLength != null && contentLength != expectedLength) return "Content-Length disagrees with Content-Range"
    if (!expectedEtag.isNullOrBlank() && expectedEtag != responseEtag) return "ETag changed or absent"
    return null
}
