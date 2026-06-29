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
import org.michimusic.link.errors.LinkException
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
        val token = deviceToken.ifEmpty { sessionToken }
        return "Bearer $token"
    }

    private fun deviceIdHeader(): String = clientDeviceId

    private suspend fun httpGet(url: String): HttpResponse = client.get(url) {
        if (isAuthenticated) header("Authorization", authHeader())
        if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
    }

    private fun HttpStatusCode.toLinkException(): LinkException = when {
        this == HttpStatusCode.Unauthorized -> LinkException.Unauthorized
        this == HttpStatusCode.Forbidden -> LinkException.Revoked
        else -> LinkException.ServerError("$value", "HTTP $value")
    }

    // --- Server Info ---

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/api/ping")
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getServerInfo(): Result<ServerInfoDto> = withContext(Dispatchers.IO) {
        try {
            Result.success(client.get("$baseUrl/api/v1/server/info").body<ServerInfoDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Discovery Info (legacy) ---

    suspend fun fetchDiscoveryInfo(): Result<ServerInfoDto> = withContext(Dispatchers.IO) {
        try {
            Result.success(client.get("$baseUrl/api/discovery/info").body<ServerInfoDto>())
        } catch (e: Exception) {
            Result.failure(e)
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
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Forbidden)
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
            val body = response.body<PairConfirmResponseDto>()
            deviceToken = body.deviceToken.ifEmpty { body.sessionToken }
            if (deviceToken.isBlank()) return@withContext Result.failure(LinkException.InvalidCredentials)
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pairStartLegacy(
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
            val response = client.post("$baseUrl/api/pair/start") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Forbidden)
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
    ): Result<PairConfirmResponseDto> = withContext(Dispatchers.IO) {
        try {
            val request = PairConfirmRequestDto(
                pairingId = pairingId,
                username = username,
                password = password,
                clientDeviceId = clientDeviceId,
            )
            val response = client.post("$baseUrl/api/pair/confirm") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.InvalidCredentials)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Forbidden)
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
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchLibrary(): Result<LibraryResponseDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/library")
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
            Result.success(response.body<LibraryResponseDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Search ---

    suspend fun search(query: String): Result<List<org.michimusic.link.dto.TrackResponseDto>> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/search?q=$query")
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
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
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
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

    suspend fun downloadTrack(
        trackId: String,
        outputStream: OutputStream,
    ): Result<Long> = streamTrack(trackId, outputStream)

    suspend fun fetchCover(
        coverId: String,
        outputStream: OutputStream,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/artwork/$coverId")
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
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

    suspend fun fetchCoverLegacy(
        coverId: String,
        outputStream: OutputStream,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/cover/$coverId")
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
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
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
            Result.success(response.body<SyncManifestDto>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSyncManifestDelta(deviceId: String, manifestId: String): Result<SyncManifestDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/sync/manifest/delta?device_id=$deviceId&manifest_id=$manifestId")
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
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
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
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
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
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
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Queue ---

    suspend fun getQueue(): Result<QueueDto> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$baseUrl/api/v1/queue")
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
            Result.success(response.body<QueueDto>())
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
            if (response.status == HttpStatusCode.Unauthorized) return@withContext Result.failure(LinkException.Unauthorized)
            if (response.status == HttpStatusCode.Forbidden) return@withContext Result.failure(LinkException.Revoked)
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
