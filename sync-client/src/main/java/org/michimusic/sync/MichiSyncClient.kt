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
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.core.models.FavoritesResponse
import org.michimusic.core.models.HistoryEntry
import org.michimusic.core.models.HistoryResponse
import org.michimusic.core.models.LibraryResponse
import org.michimusic.core.models.RegisterRequest
import org.michimusic.core.models.RegisterResponse
import org.michimusic.core.models.SyncManifest
import org.michimusic.core.models.SyncStateEntry
import org.michimusic.core.models.TrackDto
import java.io.OutputStream

class MichiSyncClient(
    private val baseUrl: String,
    private var sessionToken: String = "",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    val isAuthenticated: Boolean get() = sessionToken.isNotEmpty()

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.get("$baseUrl/api/ping")
            true
        } catch (_: Exception) {
            false
        }
    }

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
            }.body<RegisterResponse>()
            sessionToken = response.sessionToken
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchLibrary(): Result<LibraryResponse> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/library") {
                header("Authorization", "Bearer $sessionToken")
            }.body<LibraryResponse>()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun streamTrack(
        trackId: String,
        outputStream: OutputStream,
        startBytes: Long = 0,
        bufferSize: Int = 65536,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/stream/$trackId") {
                header("Authorization", "Bearer $sessionToken")
                if (startBytes > 0) {
                    header("Range", "bytes=$startBytes-")
                }
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
                header("Authorization", "Bearer $sessionToken")
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
                "session_token" to sessionToken,
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
                header("Authorization", "Bearer $sessionToken")
                setBody(json.encodeToString(body))
            }
            val result = json.decodeFromString<Map<String, Int>>(response.body())
            Result.success(result["synced"] ?: 0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun search(query: String): Result<List<TrackDto>> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/search?q=$query") {
                header("Authorization", "Bearer $sessionToken")
            }
            val result = json.decodeFromString<LibraryResponse>(response.body())
            Result.success(result.tracks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchFavorites(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/favorites") {
                header("Authorization", "Bearer $sessionToken")
            }
            val result = json.decodeFromString<FavoritesResponse>(response.body())
            Result.success(result.tracks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchHistory(): Result<List<HistoryEntry>> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/history") {
                header("Authorization", "Bearer $sessionToken")
            }
            val result = json.decodeFromString<HistoryResponse>(response.body())
            Result.success(result.entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSyncManifest(deviceId: String): Result<SyncManifest> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("$baseUrl/api/sync/manifest?device_id=$deviceId") {
                header("Authorization", "Bearer $sessionToken")
            }.body<SyncManifest>()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        client.close()
    }
}
