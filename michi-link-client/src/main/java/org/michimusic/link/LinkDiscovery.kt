package org.michimusic.link

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

open class LinkDiscovery(
    private val context: Context? = null,
) : DefaultLifecycleObserver {

    companion object {
        private const val MULTICAST_GROUP = "224.0.0.167"
        private const val MULTICAST_PORT = 53318
        private const val BUFFER_SIZE = 8192
        private const val PEER_TIMEOUT_MS = 90_000L
        // A31: receiver-local bound, never an instruction to broadcast/poll.
        private const val MAX_OBSERVED_PEERS = 128
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val peerLastSeen = mutableMapOf<String, Long>()
    private val signedSecurity = LinkSignedAnnounceSecurity()

    private val _peers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    open val peers: StateFlow<Map<String, DiscoveredPeer>> = _peers.asStateFlow()

    private val _events = MutableSharedFlow<DiscoveryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DiscoveryEvent> = _events.asSharedFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var isRunning = false

    init {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (isRunning) acquireMulticastLock()
    }

    override fun onPause(owner: LifecycleOwner) {
        releaseMulticastLock()
    }

    override fun onStop(owner: LifecycleOwner) {
        isRunning = false
        releaseMulticastLock()
        closeSocket()
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        isRunning = true
        acquireMulticastLock()

        val group = try {
            InetAddress.getByName(MULTICAST_GROUP)
        } catch (_: Exception) {
            isRunning = false
            releaseMulticastLock()
            return@withContext
        }
        val sock = try {
            MulticastSocket(MULTICAST_PORT).also {
                it.reuseAddress = true
                it.joinGroup(group)
                it.soTimeout = 1000
                socket = it
            }
        } catch (_: Exception) {
            isRunning = false
            releaseMulticastLock()
            return@withContext
        }

        val buffer = ByteArray(BUFFER_SIZE)

        while (isActive && isRunning) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val msg = json.decodeFromString<AnnounceMessage>(data)
                val host = packet.address?.hostAddress ?: "unknown"
                processAnnounce(msg, host)
            } catch (_: java.net.SocketTimeoutException) {
                cleanStalePeers()
            } catch (error: Exception) {
                // A malformed/untrusted UDP advertisement must not kill discovery.
                // A closed socket during stop is a legitimate lifecycle transition.
                if (!isRunning || !isActive) break
                // Do not log remote datagram contents, tokens or identifiers.
            }
        }
        // Release even if a peer sends invalid data or the socket closes unexpectedly.
        isRunning = false
        closeSocket()
        releaseMulticastLock()
    }

    fun processAnnounce(
        msg: AnnounceMessage,
        hostAddress: String,
        now: Long = System.currentTimeMillis()
    ) {
        if (msg.port !in 1..65535) return
        val level = signedSecurity.verify(msg, hostAddress, now)
        if (level == LinkSignedAnnounceSecurity.Level.INVALID) return
        val key = if (msg.deviceId.isNotBlank()) msg.deviceId else "$hostAddress:${msg.port}"
        // UDP discovery never elevates Home authority. A signed device may
        // migrate IP only if its existing Ed25519 identity remains identical.
        val previous = _peers.value[key]
        if (previous?.identityTrust == "VERIFIED" &&
            (level != LinkSignedAnnounceSecurity.Level.VERIFIED || previous.michiId != msg.michiId)) return
        if (previous?.ip?.let { it != hostAddress } == true &&
            (level != LinkSignedAnnounceSecurity.Level.VERIFIED || previous.michiId != msg.michiId ||
                // A53: a signed hostname alone is NOT evidence of this UDP source IP.
                // A replay from a different host must not replace the live route.
                !LinkAnnounceSourceBindingPolicy.mayMove(msg.host, hostAddress))) return
        if (msg.type != "goodbye" && previous == null && _peers.value.size >= MAX_OBSERVED_PEERS) {
            // Prioritize valid Ed25519 identities over untrusted LAN hints.
            // Never evict verified peers for an unsigned flood; all memory is
            // bounded even on guest Wi-Fi with hostile packets.
            val oldestUntrusted = peerLastSeen.entries
                .asSequence()
                .filter { _peers.value[it.key]?.identityTrust != "VERIFIED" }
                .minByOrNull { it.value }?.key
            if (oldestUntrusted == null) return
            val evictedAlias = _peers.value[oldestUntrusted]?.alias
            _peers.value = _peers.value - oldestUntrusted
            peerLastSeen.remove(oldestUntrusted)
            _events.tryEmit(DiscoveryEvent.PeerLost(peerKey = oldestUntrusted, alias = evictedAlias))
        }
        if (msg.type == "goodbye") {
            val peerAlias = _peers.value[key]?.alias ?: msg.alias.takeIf { it.isNotBlank() }
            _peers.value = _peers.value - key
            peerLastSeen.remove(key)
            _events.tryEmit(DiscoveryEvent.PeerLost(peerKey = key, alias = peerAlias))
        } else {
            // MIM-03: a verified cryptographic identity is the dedup key. A
            // firmware migration that changes device_id/alias must replace the
            // old observation, not create a second peer for the same device.
            if (level == LinkSignedAnnounceSecurity.Level.VERIFIED && msg.michiId.isNotBlank()) {
                val staleIdentity = _peers.value
                    .filterValues { it.michiId == msg.michiId && it.deviceId != msg.deviceId }
                    .keys
                if (staleIdentity.isNotEmpty()) {
                    val lost = staleIdentity.map { id -> id to _peers.value[id]?.alias }
                    _peers.value = _peers.value - staleIdentity
                    staleIdentity.forEach { peerLastSeen.remove(it) }
                    lost.forEach { (id, alias) -> _events.tryEmit(DiscoveryEvent.PeerLost(id, alias)) }
                }
            }
            val peer = DiscoveredPeer(
                alias = msg.name.ifBlank { msg.alias },
                ip = hostAddress,
                port = msg.port,
                deviceType = msg.service.ifBlank { msg.device },
                deviceId = msg.deviceId,
                version = msg.apiVersion.ifBlank { msg.version },
                authRequired = msg.authRequired,
                identityTrust = level.name,
                michiId = if (level == LinkSignedAnnounceSecurity.Level.VERIFIED) msg.michiId else "",
                service = msg.service,
            )
            val wasNew = key !in _peers.value
            _peers.value = _peers.value + (key to peer)
            peerLastSeen[key] = now
            if (wasNew) {
                _events.tryEmit(DiscoveryEvent.PeerFound(peer))
            }
        }
    }

    suspend fun stop(preservePeers: Boolean = false) = withContext(Dispatchers.IO) {
        isRunning = false
        closeSocket()
        releaseMulticastLock()
        if (!preservePeers) {
            _peers.value = emptyMap()
            peerLastSeen.clear()
        }
    }

    private fun closeSocket() {
        try {
            val group = InetAddress.getByName(MULTICAST_GROUP)
            socket?.leaveGroup(group)
        } catch (_: Exception) {}
        socket?.close()
        socket = null
    }

    fun cleanStalePeers(now: Long = System.currentTimeMillis()) {
        val stale = peerLastSeen.filterValues { now - it > PEER_TIMEOUT_MS }.keys.toSet()
        if (stale.isNotEmpty()) {
            val staleEntries = stale.map { key -> key to _peers.value[key]?.alias }
            _peers.value = _peers.value - stale
            stale.forEach { peerLastSeen.remove(it) }
            staleEntries.forEach { (key, alias) ->
                _events.tryEmit(DiscoveryEvent.PeerLost(peerKey = key, alias = alias))
            }
        }
    }

    // A52: lifecycle and IO may race; test+acquire must share one monitor.
    @Synchronized
    private fun acquireMulticastLock() {
        // A31: duplicate lifecycle callbacks must not leave a replaced Wi-Fi
        // MulticastLock acquired and unreachable after screen navigation.
        if (multicastLock != null) return
        val wifi = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val acquired = runCatching {
            wifi.createMulticastLock("michi-discovery").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }.getOrNull()
        multicastLock = acquired
    }

    @Synchronized
    private fun releaseMulticastLock() {
        multicastLock?.let {
            try { it.release() } catch (_: Exception) {}
            multicastLock = null
        }
    }
}

sealed class DiscoveryEvent {
    data class PeerFound(val peer: DiscoveredPeer) : DiscoveryEvent()
    data class PeerLost(val peerKey: String, val alias: String? = null) : DiscoveryEvent()
}
