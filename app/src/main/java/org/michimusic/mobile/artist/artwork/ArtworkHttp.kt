package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.michimusic.mobile.artist.MAX_RETRY_AFTER_MS
import org.michimusic.mobile.artist.retryAfterDelayMs
import org.michimusic.mobile.network.MICHI_HTTP_USER_AGENT
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ArtworkHttpException(val status: Int, val retryAfterMs: Long = 0L, message: String? = null) :
    IOException(message ?: "Proveedor HTTP $status")

/**
 * Bounded, origin-checked metadata transport for artwork providers. Redirects are never
 * followed and every response is capped before parsing.
 */
class ArtworkHttp(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun readJson(url: String, allowedHosts: Set<String>): JsonObject {
        val body = readBytes(url, allowedHosts, MAX_JSON_BYTES) { response ->
            if (response.header("Content-Type")?.contains("json", ignoreCase = true) != true) {
                throw IOException("Proveedor no devolvió JSON")
            }
        }
        return try {
            json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw IOException("Estructura JSON no válida")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ArtworkDecodeException("JSON de proveedor no válido", error)
        }
    }

    suspend fun readText(url: String, allowedHosts: Set<String>, maxBytes: Int = MAX_ORIGIN_BYTES): String =
        readBytes(url, allowedHosts, maxBytes) { response ->
            if (response.header("Content-Type")?.contains("html", ignoreCase = true) != true) {
                throw IOException("Origen sin HTML")
            }
        }.toString(Charsets.UTF_8)

    private suspend fun readBytes(
        url: String,
        allowedHosts: Set<String>,
        maxBytes: Int,
        validate: (Response) -> Unit,
    ): ByteArray = readWithRetry {
        readOnce(url, allowedHosts, maxBytes, validate)
    }

    private suspend fun readWithRetry(block: suspend () -> ByteArray): ByteArray {
        var retries = 0
        while (true) {
            try {
                return block()
            } catch (error: ArtworkHttpException) {
                if (error.status != 429 && error.status != 503 || retries >= 2) throw error
                val wait = error.retryAfterMs.takeIf { it > 0L } ?: (1_000L shl retries)
                delay(wait.coerceAtMost(MAX_RETRY_AFTER_MS))
                retries++
            }
        }
    }

    private suspend fun readOnce(
        url: String,
        allowedHosts: Set<String>,
        maxBytes: Int,
        validate: (Response) -> Unit,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val endpoint = url.toHttpUrlOrNull()
        if (endpoint == null || endpoint.scheme != "https" || endpoint.host !in allowedHosts) {
            continuation.resumeWithException(IOException("Origen de artwork no autorizado"))
            return@suspendCancellableCoroutine
        }
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", MICHI_HTTP_USER_AGENT)
            .header("Accept", "application/json, text/html;q=0.8")
            .build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) {
                            throw ArtworkHttpException(it.code, retryAfterDelayMs(it.header("Retry-After")))
                        }
                        validate(it)
                        it.body?.byteStream()?.use { stream ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > maxBytes) {
                                    throw IOException("Respuesta de artwork demasiado grande")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: throw IOException("Respuesta vacía")
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

class ArtworkDecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal const val MAX_JSON_BYTES = 256 * 1024
internal const val MAX_ORIGIN_BYTES = 600 * 1024
