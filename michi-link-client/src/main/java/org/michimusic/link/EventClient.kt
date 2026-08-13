package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class ServerEvent(
    val type: String = "",
    val payload: JsonElement? = null,
    val timestamp: String = "",
)

class EventClient(
    private val baseUrl: String,
    private val token: String,
    private val clientDeviceId: String = "",
    private val client: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
        }
    },
) {
    private val _events = MutableSharedFlow<ServerEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun connect(scope: CoroutineScope) {
        disconnect()
        reconnectJob = scope.launch {
            while (isActive) {
                try {
                    connectionJob = listen(scope)
                    connectionJob?.join()
                } catch (_: Exception) {
                }
                delay(5_000L)
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        connectionJob?.cancel()
        connectionJob = null
    }

    private suspend fun listen(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        try {
            client.prepareGet("$baseUrl/api/v1/events/sse") {
                header("Authorization", "Bearer $token")
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
            }.execute { response ->
                val channel = response.bodyAsChannel()
                while (isActive && !channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data.isNotEmpty() && data != ":keepalive") {
                            try {
                                val event = json.decodeFromString<ServerEvent>(data)
                                _events.emit(event)
                            } catch (_: Throwable) { }
                        }
                    }
                }
            }
        } catch (_: Throwable) { }
    }
}
