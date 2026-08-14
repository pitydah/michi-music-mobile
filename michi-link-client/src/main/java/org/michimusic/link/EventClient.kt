package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

enum class EventConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _events = MutableSharedFlow<ServerEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(EventConnectionState.DISCONNECTED)
    val connectionState: StateFlow<EventConnectionState> = _connectionState.asStateFlow()

    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun connect(scope: CoroutineScope) {
        disconnect()
        _connectionState.value = EventConnectionState.CONNECTING
        reconnectJob = scope.launch(ioDispatcher) {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    _connectionState.value = EventConnectionState.CONNECTING
                    connectionJob = listen(scope) {
                        _connectionState.value = EventConnectionState.CONNECTED
                        backoffMs = 1_000L // Reset backoff upon successful connection
                    }
                    connectionJob?.join()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    break
                } catch (_: Exception) {
                    _connectionState.value = EventConnectionState.ERROR
                }

                if (isActive) {
                    _connectionState.value = EventConnectionState.CONNECTING
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
            _connectionState.value = EventConnectionState.DISCONNECTED
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        connectionJob?.cancel()
        connectionJob = null
        _connectionState.value = EventConnectionState.DISCONNECTED
    }

    private suspend fun listen(
        scope: CoroutineScope,
        onConnected: () -> Unit = {},
    ): Job = scope.launch(ioDispatcher) {
        try {
            client.prepareGet("$baseUrl/api/v1/events/sse") {
                header("Authorization", "Bearer $token")
                if (clientDeviceId.isNotEmpty()) header("X-Michi-Device-Id", clientDeviceId)
            }.execute { response ->
                onConnected()
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
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Normal cancellation
        } catch (_: Throwable) {
            _connectionState.value = EventConnectionState.ERROR
        }
    }
}
