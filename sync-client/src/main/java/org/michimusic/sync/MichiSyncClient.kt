package org.michimusic.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.core.models.DiscoveryInfo
import org.michimusic.core.models.FavoritesResponse
import org.michimusic.core.models.HistoryEntry
import org.michimusic.core.models.HistoryResponse
import org.michimusic.core.models.LibraryResponse
import org.michimusic.core.models.PairConfirmRequest
import org.michimusic.core.models.PairConfirmResponse
import org.michimusic.core.models.PairStartRequest
import org.michimusic.core.models.PairStartResponse
import org.michimusic.core.models.RegisterRequest
import org.michimusic.core.models.RegisterResponse
import org.michimusic.core.models.SearchResponse
import org.michimusic.core.models.SyncManifest
import org.michimusic.core.models.SyncStateEntry
import org.michimusic.core.models.TrackDto
import java.io.OutputStream

class MichiSyncClient(
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

    private fun isUnauthorized(status: HttpStatusCode): Boolean =
        status == HttpStatusCode.Unauthorized

    private fun isForbidden(status: HttpStatusCode): Boolean =
        status == HttpStatusCode.Forbidden

    // --- Discovery & Pairing ---

    suspend fun fetchDiscoveryInfo(): Result<DiscoveryInfo> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/discovery/info")
            val body = response.body<DiscoveryInfo>()
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pairStart(
        alias: String,
        deviceModel: String,
        clientDeviceId: String,
    ): Result<PairStartResponse> = withContext(Dispatchers.IO) {
        try {
            val request = PairStartRequest(
                alias = alias,
                deviceModel = deviceModel,
                clientDeviceId = clientDeviceId,
            )
            val response = client.post("$baseUrl/api/pair/start") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (isUnauthorized(response.status)) {
                return@withContext Result.failure(PairingException.Unauthorized)
            }
            if (isForbidden(response.status)) {
                return@withContext Result.failure(PairingException.Forbidden)
            }
            val body = response.body<PairStartResponse>()
            Result.success(body)
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
        clientVersion: String = "",
    ): Result<PairConfirmResponse> = withContext(Dispatchers.IO) {
        try {
            val request = PairConfirmRequest(
                pairingId = pairingId,
                username = username,
                password = password,
                clientDeviceId = clientDeviceId,
                alias = alias,
                deviceModel = deviceModel,
                clientVersion = clientVersion,
            )
            val response = client.post("$baseUrl/api/pair/confirm") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (isUnauthorized(response.status)) {
                return@withContext Result.failure(PairingException.InvalidCredentials)
            }
            if (isForbidden(response.status)) {
                return@withContext Result.failure(PairingException.Forbidden)
            }
            val body = response.body<PairConfirmResponse>()
            deviceToken = body.deviceToken.ifEmpty { body.sessionToken }
            if (deviceToken.isBlank()) {
                return@withContext Result.failure(PairingException.InvalidCredentials)
            }
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Legacy register (inseguro, para servidores sin pairing) ---

    suspend fun register(
        alias: String,
        deviceModel: String = "",
        clientDeviceId: String = "",
    ): Result<RegisterResponse> = withContext(Dispatchers.IO) {
        try {
            val request = RegisterRequest(
                alias = alias,
                device = "android",
                deviceModel = deviceModel,
                clientDeviceId = clientDeviceId,
            )
            val response = client.post("$baseUrl/api/register") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (isUnauthorized(response.status)) {
                return@withContext Result.failure(PairingException.Unauthorized)
            }
            if (isForbidden(response.status)) {
                return@withContext Result.failure(PairingException.Forbidden)
            }
            val body = response.body<RegisterResponse>()
            sessionToken = body.sessionToken
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Protected API calls ---

    private suspend fun <T> authenticatedGet(
        url: String,
        parser: suspend (String) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val response = client.get(url) {
                header("Authorization", authHeader())
                if (clientDeviceId.isNotEmpty()) {
                    header("X-Michi-Device-Id", clientDeviceId)
                }
            }
            when {
                isUnauthorized(response.status) -> Result.failure(PairingException.Unauthorized)
                isForbidden(response.status) -> Result.failure(PairingException.Revoked)
                else -> {
                    val body = response.body<String>()
                    Result.success(parser(body))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/api/ping")
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchLibrary(): Result<LibraryResponse> =
        authenticatedGet("$baseUrl/api/library") { body ->
            json.decodeFromString<LibraryResponse>(body)
        }

    suspend fun streamTrack(
        trackId: String,
        outputStream: OutputStream,
        startBytes: Long = 0,
        bufferSize: Int = 65536,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/stream/$trackId") {
                header("Authorization", authHeader())
                if (clientDeviceId.isNotEmpty()) {
                    header("X-Michi-Device-Id", clientDeviceId)
                }
                if (startBytes > 0) {
                    header("Range", "bytes=$startBytes-")
                }
            }
            when {
                isUnauthorized(response.status) -> return@withContext Result.failure(PairingException.Unauthorized)
                isForbidden(response.status) -> return@withContext Result.failure(PairingException.Revoked)
            }
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

    suspend fun fetchCover(
        coverId: String,
        outputStream: OutputStream,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/cover/$coverId") {
                header("Authorization", authHeader())
                if (clientDeviceId.isNotEmpty()) {
                    header("X-Michi-Device-Id", clientDeviceId)
                }
            }
            when {
                isUnauthorized(response.status) -> return@withContext Result.failure(PairingException.Unauthorized)
                isForbidden(response.status) -> return@withContext Result.failure(PairingException.Revoked)
            }
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

    suspend fun syncState(
        entries: List<SyncStateEntry>,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf(
                "tracks" to entries.map { e ->
                    mapOf(
                        "track_id" to e.trackId,
                        "play_count" to e.playCount,
                        "favorite" to e.favorite,
                    )
                },
            )
            val response = client.post("$baseUrl/api/sync/state") {
                contentType(ContentType.Application.Json)
                header("Authorization", authHeader())
                if (clientDeviceId.isNotEmpty()) {
                    header("X-Michi-Device-Id", clientDeviceId)
                }
                setBody(json.encodeToString(body))
            }
            when {
                isUnauthorized(response.status) -> return@withContext Result.failure(PairingException.Unauthorized)
                isForbidden(response.status) -> return@withContext Result.failure(PairingException.Revoked)
            }
            val result = json.decodeFromString<Map<String, Int>>(response.body())
            Result.success(result["synced"] ?: 0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun search(query: String): Result<List<TrackDto>> =
        authenticatedGet("$baseUrl/api/search?q=$query") { body ->
            json.decodeFromString<SearchResponse>(body).results
        }

    suspend fun fetchFavorites(): Result<List<String>> =
        authenticatedGet("$baseUrl/api/favorites") { body ->
            json.decodeFromString<FavoritesResponse>(body).tracks
        }

    suspend fun fetchHistory(): Result<List<HistoryEntry>> =
        authenticatedGet("$baseUrl/api/history") { body ->
            json.decodeFromString<HistoryResponse>(body).entries
        }

    suspend fun fetchSyncManifest(deviceId: String): Result<SyncManifest> =
        authenticatedGet("$baseUrl/api/sync/manifest?device_id=$deviceId") { body ->
            json.decodeFromString<SyncManifest>(body)
        }

    fun close() {
        client.close()
    }
}

sealed class PairingException(message: String) : Exception(message) {
    data object Unauthorized : PairingException("Se requiere autenticación")
    data object Forbidden : PairingException("Acceso denegado")
    data object InvalidCredentials : PairingException("Credenciales incorrectas")
    data object Revoked : PairingException("Dispositivo revocado")
}
