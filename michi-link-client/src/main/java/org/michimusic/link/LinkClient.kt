package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import org.michimusic.link.dto.LibraryResponseDto
import org.michimusic.link.dto.ManifestTrackDto
import org.michimusic.link.dto.PairConfirmRequestDto
import org.michimusic.link.dto.PairConfirmResponseDto
import org.michimusic.link.dto.PairStartRequestDto
import org.michimusic.link.dto.PairStartResponseDto
import org.michimusic.link.dto.PlaybackControlRequestDto
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.QueueDto
import org.michimusic.link.dto.QueueJumpRequestDto
import org.michimusic.link.dto.SearchResponseDto
import org.michimusic.link.dto.ServerInfoDto
import org.michimusic.link.dto.SyncManifestDto
import org.michimusic.link.dto.SyncStateEntry
import org.michimusic.link.dto.TokenRefreshRequestDto
import org.michimusic.link.dto.TokenRefreshResponseDto
import org.michimusic.link.dto.TrackListResponseDto
import org.michimusic.link.errors.LinkException
import org.michimusic.link.errors.LinkErrorResponse
import java.io.OutputStream

class LinkClient(
    val baseUrl: String,
    var sessionToken: String = "",
    var deviceToken: String = "",
    var clientDeviceId: String = "",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    val isAuthenticated: Boolean get() = sessionToken.isNotEmpty() || deviceToken.isNotEmpty()

    private fun authHeader(): String {
        return "Bearer ${deviceToken.ifEmpty { sessionToken }}"
    }

    private suspend fun httpGet(url: String): HttpResponse = client.get(url) {
        if (isAuthenticated) header("Authorization", authHeader())
        if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
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
            Result.success(client.get("$baseUrl/api/v1/server/info").body<ServerInfoDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
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
    ): Result<PairConfirmResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = PairConfirmRequestDto(
                pairingId = pairingId,
                username = username,
                password = password,
                clientDeviceId = clientDeviceId,
                alias = alias,
                deviceModel = deviceModel,
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

    suspend fun refreshToken(refreshToken: String): Result<TokenRefreshResponseDto> = withContext(Dispatchers.IO) {
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
            Result.success(LibraryResponseDto(tracks = body.items, total = body.total))
        } catch (e: Exception) {
            try {
                val response = httpGet("$baseUrl/api/library")
                response.status.checkError()?.let { return@withContext Result.failure(it) }
                Result.success(response.body<LibraryResponseDto>())
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    // --- Search ---

    suspend fun search(query: String): Result<List<org.michimusic.link.dto.TrackResponseDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/search?q=$query")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<SearchResponseDto>().results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Streaming ---

    suspend fun streamTrack(
        trackId: String,
        outputStream: OutputStream,
        startBytes: Long = 0,
        bufferSize: Int = 65536,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/v1/stream/$trackId") {
                header("Authorization", authHeader())
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
            val response = httpGet("$baseUrl/api/v1/artwork/$coverId")
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
            val response = httpGet("$baseUrl/api/v1/sync/manifest?device_id=$deviceId")
            response.status.checkError()?.let { return@withContext Result.failure(it) }
            Result.success(response.body<SyncManifestDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSyncManifestDelta(deviceId: String, cursor: String): Result<SyncManifestDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/sync/manifest/delta?device_id=$deviceId&cursor=$cursor")
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
        client.close()
    }
}

@kotlinx.serialization.Serializable
private data class AltQueueDto(
    val items: List<org.michimusic.link.dto.QueueTrackDto> = emptyList(),
    @kotlinx.serialization.SerialName("currentIndex") val currentIndex: Int = -1,
)
