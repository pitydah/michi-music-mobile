package org.michimusic.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RemoteApiClient(
    private val baseUrl: String,
    private var bearerToken: String = "",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun setToken(token: String) {
        bearerToken = token
    }

    private fun buildRequest(path: String, method: String = "GET", body: String? = null): Request {
        val rb = Request.Builder()
            .url("$baseUrl$path")
            .method(method, body?.toRequestBody(jsonMediaType))
            .header("Accept", "application/json")
        if (bearerToken.isNotEmpty()) {
            rb.header("Authorization", "Bearer $bearerToken")
        }
        return rb.build()
    }

    private suspend fun execute(request: Request): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(response.body?.string() ?: "")
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchStatus(): Result<RemotePlayerState> {
        val request = buildRequest("/api/player/status")
        return execute(request).map { body ->
            val obj = json.parseToJsonElement(body).jsonObject
            RemotePlayerState(
                state = obj["state"]?.jsonPrimitive?.content ?: "idle",
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                artist = obj["artist"]?.jsonPrimitive?.content ?: "",
                album = obj["album"]?.jsonPrimitive?.content ?: "",
                duration = obj["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                position = obj["position"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                volume = obj["volume"]?.jsonPrimitive?.content?.toIntOrNull() ?: 70,
                sourceType = obj["source_type"]?.jsonPrimitive?.content ?: "",
                sourceLabel = obj["source_label"]?.jsonPrimitive?.content ?: "",
                destination = obj["destination"]?.jsonPrimitive?.content ?: "local",
                coverUrl = obj["cover_url"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    suspend fun play(): Result<String> {
        val request = buildRequest("/api/player/play", "POST")
        return execute(request)
    }

    suspend fun pause(): Result<String> {
        val request = buildRequest("/api/player/pause", "POST")
        return execute(request)
    }

    suspend fun stop(): Result<String> {
        val request = buildRequest("/api/player/stop", "POST")
        return execute(request)
    }

    suspend fun next(): Result<String> {
        val request = buildRequest("/api/player/next", "POST")
        return execute(request)
    }

    suspend fun previous(): Result<String> {
        val request = buildRequest("/api/player/previous", "POST")
        return execute(request)
    }

    suspend fun setVolume(volume: Int): Result<String> {
        val body = """{"volume":$volume}"""
        val request = buildRequest("/api/player/volume", "POST", body)
        return execute(request)
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
    }
}
