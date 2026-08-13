package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
import org.michimusic.link.dto.QueueReorderRequest
import org.michimusic.link.dto.QueueSaveRequest
import org.michimusic.link.dto.QueueTransferRequest
import org.michimusic.link.dto.QueueTransferResponse
import org.michimusic.link.dto.RateRequest
import org.michimusic.link.dto.RateResponse
import org.michimusic.link.dto.ReceiverDto
import org.michimusic.link.dto.ReceiverSessionRequest
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

class LinkClient private constructor(
    baseUrl: String,
    @Volatile var sessionToken: String,
    @Volatile var deviceToken: String,
    @Volatile var clientDeviceId: String,
    httpClient: HttpClient? = null,
) {
    constructor(
        baseUrl: String,
        sessionToken: String = "",
        deviceToken: String = "",
        clientDeviceId: String = "",
    ) : this(baseUrl, sessionToken, deviceToken, clientDeviceId, null)

    val baseUrl: String = LinkClientConfig.normalizeBaseUrl(baseUrl)
    @Volatile var tokenRefreshSupported: Boolean? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val ownsClient = httpClient == null

    val httpClient: HttpClient get() = client
    private val client = httpClient ?: HttpClient {
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
        ): LinkClient = LinkClient(baseUrl, sessionToken, deviceToken, clientDeviceId, httpClient)
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

    private suspend fun httpGet(url: String): HttpResponse = withContext(Dispatchers.IO) {
        client.get(url) {
            if (isAuthenticated) header("Authorization", authHeader())
            if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
        this == HttpStatusCode.NotImplemented -> LinkException.NotImplemented
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
                setBody(json.encodeToString(body))
            }
            response.status.checkError(response.bodyOrNull())?.let { return@withContext Result.failure(it) }
            Result.success(response.body<T>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun HttpResponse.bodyOrNull(): String? = try { body() } catch (_: Exception) { null }

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
            val info = client.get("$baseUrl/api/v1/server/info").body<ServerInfoDto>()
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

    suspend fun pairStart(
        alias: String,
        deviceModel: String,
        clientDeviceId: String,
    ): Result<PairStartResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = PairStartRequestDto(
                alias = alias,
                deviceModel = deviceModel,
                clientDeviceId = clientDeviceId,
            )
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

    suspend fun pairConfirm(
        pairingId: String,
        username: String,
        password: String,
        clientDeviceId: String,
        alias: String = "",
        deviceModel: String = "",
        pin: String = "",
    ): Result<PairConfirmResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = PairConfirmRequestDto(
                pairingId = pairingId,
                username = username,
                password = password,
                clientDeviceId = clientDeviceId,
                alias = alias,
                deviceModel = deviceModel,
                pin = pin,
            )
            val response = client.post("$baseUrl/api/v1/pair/confirm") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.InvalidCredentials)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Forbidden)
            response.status.checkError(response.body())?.let { return@withContext Result.failure(it) }
            val body = response.body<PairConfirmResponseDto>()
            deviceToken = body.deviceToken.ifEmpty { body.sessionToken }
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
                clientDeviceId = clientDeviceId,
            )
            val response = client.post("$baseUrl/api/v1/token/refresh") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.NotImplemented) {
                tokenRefreshSupported = false
                return@withContext Result.failure(LinkException.NotImplemented)
            }
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.TokenExpired)
            val body = response.body<TokenRefreshResponseDto>()
            deviceToken = body.deviceToken
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
        try {
            val response = client.get("$baseUrl/api/v1/stream/${encodePathSegment(trackId)}") {
                if (isAuthenticated) header("Authorization", authHeader())
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
                if (startBytes > 0) header("Range", "bytes=$startBytes-")
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            val channel = response.bodyAsChannel()
            var totalRead = 0L
            val buffer = ByteArray(bufferSize)
            while (isActive && !channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                outputStream.write(buffer, 0, read)
                totalRead += read
            }
            outputStream.flush()
            Result.success(totalRead)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
            // If empty and server uses items/currentIndex, try alternate parsing
            if (parsed.tracks.isEmpty() && raw.contains("items")) {
                val alt = json.decodeFromString<AltQueueDto>(raw)
                Result.success(QueueDto(
                    tracks = alt.items,
                    currentIndex = alt.currentIndex,
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
            }
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setReceiverVolume(id: String, volume: Int): Result<Unit> =
        httpPost("$baseUrl/api/v1/receivers/${encodePathSegment(id)}/volume", ReceiverVolumeRequest(volume = volume))

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
    ): Result<PairConfirmResponseDto> = withContext(Dispatchers.IO) {
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
            Result.success(PairConfirmResponseDto(deviceId = legacy.clientDeviceId, sessionToken = legacy.sessionToken))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        if (ownsClient) {
            client.close()
        }
    }
}

@kotlinx.serialization.Serializable
private data class AltQueueDto(
    val items: List<org.michimusic.link.dto.QueueTrackDto> = emptyList(),
    @kotlinx.serialization.SerialName("currentIndex") val currentIndex: Int = -1,
)
