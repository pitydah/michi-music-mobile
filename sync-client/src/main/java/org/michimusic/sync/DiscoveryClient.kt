package org.michimusic.sync

import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.michimusic.core.models.AnnounceMessage
import org.michimusic.core.models.DiscoveredPeer
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

class DiscoveryClient(
    private val context: Context,
) : DefaultLifecycleObserver {

    companion object {
        private const val MULTICAST_GROUP = "224.0.0.167"
        private const val MULTICAST_PORT = 53318
        private const val BUFFER_SIZE = 4096
        private const val PEER_TIMEOUT_MS = 15_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val peerLastSeen = mutableMapOf<String, Long>()

    private val _peers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val peers: StateFlow<Map<String, DiscoveredPeer>> = _peers.asStateFlow()

    private val _events = MutableSharedFlow<DiscoveryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DiscoveryEvent> = _events.asSharedFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: MulticastSocket? = null
    private var isRunning = false

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        if (isRunning) acquireMulticastLock()
    }

    override fun onPause(owner: LifecycleOwner) {
        releaseMulticastLock()
    }

    override fun onStop(owner: LifecycleOwner) {
        releaseMulticastLock()
        closeSocket()
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        isRunning = true
        acquireMulticastLock()

        val group = InetAddress.getByName(MULTICAST_GROUP)
        val sock = MulticastSocket(MULTICAST_PORT).also {
            it.reuseAddress = true
            it.joinGroup(group)
            it.soTimeout = 1000
            socket = it
        }

        val buffer = ByteArray(BUFFER_SIZE)

        while (isActive && isRunning) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val msg = json.decodeFromString<AnnounceMessage>(data)

                if (msg.type == "goodbye") {
                    _peers.value = _peers.value - msg.alias
                    peerLastSeen.remove(msg.alias)
                    _events.tryEmit(DiscoveryEvent.PeerLost(msg.alias))
                } else {
                    val now = System.currentTimeMillis()
                    val peer = DiscoveredPeer(
                        alias = msg.alias,
                        ip = packet.address.hostAddress ?: "unknown",
                        port = msg.port,
                        deviceType = msg.device,
                        deviceId = msg.deviceId,
                        version = msg.version,
                    )
                    val wasNew = msg.alias !in _peers.value
                    _peers.value = _peers.value + (msg.alias to peer)
                    peerLastSeen[msg.alias] = now
                    if (wasNew) {
                        _events.tryEmit(DiscoveryEvent.PeerFound(peer))
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                cleanStalePeers()
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        isRunning = false
        closeSocket()
        releaseMulticastLock()
        _peers.value = emptyMap()
        peerLastSeen.clear()
    }

    private fun closeSocket() {
        try {
            val group = InetAddress.getByName(MULTICAST_GROUP)
            socket?.leaveGroup(group)
        } catch (_: Exception) {}
        socket?.close()
        socket = null
    }

    private fun cleanStalePeers() {
        val now = System.currentTimeMillis()
        val stale = peerLastSeen.filterValues { lastSeen ->
            now - lastSeen > PEER_TIMEOUT_MS
        }.keys.toSet()
        if (stale.isNotEmpty()) {
            _peers.value = _peers.value - stale
            stale.forEach { peerLastSeen.remove(it) }
            stale.forEach { _events.tryEmit(DiscoveryEvent.PeerLost(it)) }
        }
    }

    private fun acquireMulticastLock() {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("michi-discovery").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            try { it.release() } catch (_: Exception) {}
            multicastLock = null
        }
    }
}

sealed class DiscoveryEvent {
    data class PeerFound(val peer: DiscoveredPeer) : DiscoveryEvent()
    data class PeerLost(val alias: String) : DiscoveryEvent()
}
