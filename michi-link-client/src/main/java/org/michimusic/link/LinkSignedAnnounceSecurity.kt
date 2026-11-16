package org.michimusic.link

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.michimusic.core.models.AnnounceMessage
import java.net.InetAddress
import java.util.Base64
import java.util.LinkedHashMap

/**
 * A26: Michi Link signed discovery verification; mirrors the canonical Rust
 * discovery.rs wire payload and checks (without creating a trust session).
 * Discovery is a hint even when a signature is VALID: it NEVER grants AirCat,
 * Home membership, remote-control authority, or permission to send a bearer.
 *
 * No network, threads, persistent workers, pairing, or background scanning.
 */
internal class LinkSignedAnnounceSecurity {
    enum class Level { VERIFIED, UNTRUSTED, INVALID }

    private val replay = LinkedHashMap<Pair<String, String>, Long>()
    private val urlDecoder = Base64.getUrlDecoder()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    private val allowedRoles = mapOf(
        "michi-music-player" to setOf("desktop_player", "library_master", "sync_host"),
        "michi-micro-server" to setOf("music_server", "library_host", "playback_host"),
        "michi-mobile" to setOf("mobile_player", "remote_controller", "sync_client"),
        "michi-stream-standard" to setOf("audio_receiver"),
        "michi-stream-hifi" to setOf("audio_receiver"),
    )

    @Synchronized
    fun verify(msg: AnnounceMessage, sourceIp: String, nowMs: Long): Level {
        val group = listOf(
            msg.michiId.isNotEmpty(), msg.publicKey.isNotEmpty(),
            msg.signature.isNotEmpty(), msg.timestampMs != null, msg.nonce.isNotEmpty(),
        )
        if (group.none { it }) {
            return if (msg.service.startsWith("michi-stream-")) Level.INVALID else Level.UNTRUSTED
        }
        if (!group.all { it }) return Level.INVALID
        if (msg.type != "announce" ||
            !msg.deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
            msg.name.isBlank() || msg.host.isBlank() || msg.port !in 1..65535 ||
            msg.features.isEmpty() || msg.roles.isEmpty() || msg.roles.distinct() != msg.roles ||
            msg.publicKey.length != 43 || msg.michiId.length != 43 || msg.signature.length != 86
        ) return Level.INVALID

        val roles = allowedRoles[msg.service] ?: return Level.INVALID
        if ((msg.service.startsWith("michi-stream-") &&
                (msg.apiVersion != "v1-lite" || msg.roles != listOf("audio_receiver"))) ||
            (!msg.service.startsWith("michi-stream-") && msg.apiVersion != "v1") ||
            msg.roles.any { it !in roles }
        ) return Level.INVALID

        // Validation order follows the Link Rust reference: signature, BLAKE3 ID,
        // time, nonce replay and source-host coherence.
        val key = decoded(msg.publicKey, 32) ?: return Level.INVALID
        val signature = decoded(msg.signature, 64) ?: return Level.INVALID
        val canonical = canonicalBytes(msg)
        val validSignature = runCatching {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(key, 0))
                update(canonical, 0, canonical.size)
                verifySignature(signature)
            }
        }.getOrDefault(false)
        if (!validSignature) return Level.INVALID

        // Use the same fail-closed BLAKE3 availability rule as MichiIdentity.
        // Link currently resolves BLAKE3 by reflection to support its pinned
        // BouncyCastle 1.77 catalog without a static class-load crash.
        val hash = runCatching {
            val digest = Class.forName("org.bouncycastle.crypto.digests.Blake3Digest")
                .getConstructor(Int::class.java).newInstance(256)
            digest.javaClass.getMethod("update", ByteArray::class.java, Int::class.java, Int::class.java)
                .invoke(digest, key, 0, key.size)
            ByteArray(32).also { result ->
                digest.javaClass.getMethod("doFinal", ByteArray::class.java, Int::class.java)
                    .invoke(digest, result, 0)
            }
        }.getOrNull() ?: return Level.INVALID
        if (urlEncoder.encodeToString(hash) != msg.michiId) return Level.INVALID
        val whenSigned = msg.timestampMs ?: return Level.INVALID
        if (whenSigned < 0 || nowMs < 0 ||
            (if (whenSigned >= nowMs) whenSigned - nowMs else nowMs - whenSigned) > 90_000L
        ) return Level.INVALID
        if (decodedAtLeast(msg.nonce, 16) == null) return Level.INVALID
        if (!hostCoherent(msg.host, sourceIp)) return Level.INVALID

        val replayKey = msg.michiId to msg.nonce
        // A bounded, on-demand cache; only verified messages are inserted.
        val it = replay.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (nowMs >= entry.value && nowMs - entry.value > 90_000L) it.remove()
        }
        if (replay.containsKey(replayKey)) return Level.INVALID
        replay[replayKey] = nowMs
        // A54: keep a small bounded nonce memory even BETWEEN 35-second Devices
        // scans; clearing on stop allowed a captured signed packet to replay
        // immediately in the next scan. TTL pruning still happens on-demand.
        while (replay.size > 512) replay.remove(replay.keys.first())
        return Level.VERIFIED
    }

    @Synchronized
    fun clear() { replay.clear() }

    private fun decoded(value: String, length: Int): ByteArray? = runCatching {
        urlDecoder.decode(value).takeIf { it.size == length &&
            urlEncoder.encodeToString(it) == value }
    }.getOrNull()

    private fun decodedAtLeast(value: String, minimum: Int): ByteArray? = runCatching {
        urlDecoder.decode(value).takeIf { it.size >= minimum &&
            urlEncoder.encodeToString(it) == value }
    }.getOrNull()

    private fun hostCoherent(host: String, source: String): Boolean {
        val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        val hostIsIpv4 = ipv4.matches(host) && host.split('.').all { it.toInt() in 0..255 }
        val hostIsIpv6 = host.contains(':') && host.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
        }
        if (!hostIsIpv4 && !hostIsIpv6) return true // hostname is a hint, not an identity
        return runCatching {
            InetAddress.getByName(host) == InetAddress.getByName(source)
        }.getOrDefault(false)
    }

    /** serde_json::Map canonical output: sorted object keys, original role order. */
    internal fun canonicalBytes(msg: AnnounceMessage): ByteArray {
        val fields = sortedMapOf<String, String>()
        fields["api_version"] = quote(msg.apiVersion)
        fields["device_id"] = quote(msg.deviceId)
        fields["features"] = msg.features.toSortedMap().entries.joinToString(",", "{", "}") {
            quote(it.key) + ":" + it.value.toString()
        }
        fields["host"] = quote(msg.host)
        if (msg.membershipFingerprint.isNotBlank())
            fields["membership_fingerprint"] = quote(msg.membershipFingerprint)
        if (msg.michiHomeId.isNotBlank()) fields["michi_home_id"] = quote(msg.michiHomeId)
        fields["michi_id"] = quote(msg.michiId)
        fields["name"] = quote(msg.name)
        fields["nonce"] = quote(msg.nonce)
        fields["port"] = msg.port.toString()
        fields["public_key"] = quote(msg.publicKey)
        fields["roles"] = msg.roles.joinToString(",", "[", "]", transform = ::quote)
        fields["service"] = quote(msg.service)
        fields["timestamp_ms"] = msg.timestampMs.toString()
        return fields.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + it.value }
            .toByteArray(Charsets.UTF_8)
    }

    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (char in s) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
