package org.michimusic.mobile.devices.aircat

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Explicit, operator-confirmed out-of-band Home trust store. No UDP discovery,
 * automatic approval, network requests, timers or background scans. Backed by
 * Android Keystore encryption and excluded from app backup by storage policy.
 * A QR displayed by an unauthenticated page MUST NOT be treated as trusted.
 */
data class AirCatEnrolledHomeSource(
    val sourceMichiId: String,
    val homeId: String,
    val pinnedBaseUrl: String,
    val tlsSpkiSha256: String,
)

data class AirCatEnrolledHomeAuthority(
    val homeId: String,
    val authorityMichiId: String,
    // Public verification material; deliberately never displayed as a secret.
    val authorityPublicKeyBase64Url: String,
    val sourceMichiIds: List<String>,
    val sourceBindingsSha256: String,
) {
    val sourceCount: Int get() = sourceMichiIds.size
}

class AirCatHomeEnrollmentStore(context: Context) : AirCatHomeAuthorityProvider {
    // A58: @Synchronized alone protects only one instance. Koin and Compose may
    // create different store instances that access the same preferences file.
    // All local trust mutations and administrative snapshots share one lock.
    private companion object { val mutationLock = Any() }
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = false }
    private val prefs by lazy {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(appContext, "michi_aircat_home_anchors_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    /** Only a UI following a direct administrator QR scan may call this. */
    @Synchronized
    fun enrollFromTrustedAdminQr(text: String, userConfirmedFingerprint: Boolean, now: Long): Boolean = synchronized(mutationLock) {
        if (!userConfirmedFingerprint || text.length !in 1..4096) return false
        val qr = runCatching { json.decodeFromString<AirCatTrustedEnrollmentQr>(text) }.getOrNull()
            ?: return false
        val anchor = AirCatTrustedEnrollmentPolicy.validate(qr, now) ?: return false
        // Re-enrolling the same source under a different root/URL/pin is NEVER a
        // silent update. Revoke explicitly and scan a fresh administrative QR.
        if (prefs.getBoolean("revoked-home:" + anchor.homeId, false)) return false
        val old = anchorForSource(anchor.sourceMichiId)
        if (old != null && old != anchor) return false
        val oldRoot = anchorFor(anchor.homeId)
        if (oldRoot != null && (oldRoot.homeId != anchor.homeId ||
                oldRoot.authorityMichiId != anchor.authorityMichiId ||
                oldRoot.authorityPublicKeyBase64Url != anchor.authorityPublicKeyBase64Url)) return false
        return prefs.edit()
            .putString("source:" + anchor.sourceMichiId, json.encodeToString(anchor.toRecord()))
            .putString("home:" + anchor.homeId, json.encodeToString(anchor.toRecord()))
            .commit() // Durable before the UI may report enrollment successful.
    }

    /**
     * A56: local, read-only operator inventory. No discovery, QR requirement,
     * resident collector or network probing. Never return private authority keys.
     * Always use the source: namespace; a shared home: entry alone does not
     * prove that any source is still enrolled.
     */
    @Synchronized
    fun enrolledSources(): List<AirCatEnrolledHomeSource> = synchronized(mutationLock) {
        prefs.all.keys.asSequence()
            .filter { it.startsWith("source:") }
            .mapNotNull { key ->
                val id = key.removePrefix("source:")
                val anchor = anchorForSource(id) ?: return@mapNotNull null
                if (anchor.sourceMichiId != id ||
                    anchor.homeId != anchor.authorityMichiId) return@mapNotNull null
                val root = anchorFor(anchor.homeId) ?: return@mapNotNull null
                if (root.authorityMichiId != anchor.authorityMichiId ||
                    root.authorityPublicKeyBase64Url != anchor.authorityPublicKeyBase64Url)
                    return@mapNotNull null
                AirCatEnrolledHomeSource(
                    sourceMichiId = id,
                    homeId = anchor.homeId,
                    pinnedBaseUrl = anchor.pinnedBaseUrl,
                    tlsSpkiSha256 = anchor.tlsSpkiSha256,
                )
            }
            .sortedBy { it.sourceMichiId }
            .take(128)
            .toList()
    }

    /**
     * A56: optimistic compare-and-revoke. An out-of-date UI cannot revoke a
     * newly re-enrolled peer with the same identity but changed Home or TLS pin.
     * This does not remove the shared Home root or other source records.
     */
    @Synchronized
    fun revokeSourceIfUnchanged(expected: AirCatEnrolledHomeSource): Boolean = synchronized(mutationLock) {
        val current = anchorForSource(expected.sourceMichiId) ?: return false
        if (current.homeId != expected.homeId ||
            current.pinnedBaseUrl != expected.pinnedBaseUrl ||
            current.tlsSpkiSha256 != expected.tlsSpkiSha256) return false
        return prefs.edit().remove("source:" + expected.sourceMichiId).commit()
    }

    /**
     * A57: operator-only Home authority inventory. Enumerate exclusively local
     * Keystore-backed records; roots are independent and source counts are exact
     * for valid source records, not limited by the UI's 128-item source display.
     */
    @Synchronized
    fun enrolledHomes(): List<AirCatEnrolledHomeAuthority> = synchronized(mutationLock) {
        prefs.all.keys.asSequence()
            .filter { it.startsWith("home:") }
            .map { it.removePrefix("home:") }
            .filter { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
            .sorted()
            .take(64)
            .mapNotNull { homeSnapshot(it) }
            .toList()
    }

    /**
     * A61: root revocation must cover EVERY parseable record whose persisted
     * Home ID matches this root, including a child stored under a stale or
     * malformed source key. A root-level tombstone still denies any unparseable
     * orphan that cannot safely be assigned to one authority.
     *
     * Inventory UI displays only valid bindings (enrolledSources). Destructive
     * root revocation sees the wider set of attributable records instead.
     */
    private data class StoredSourceInHome(
        val storageId: String,
        val anchor: AirCatHomeAnchor,
    )

    private fun sourceRecordsForHome(homeId: String): List<StoredSourceInHome> =
        prefs.all.keys.asSequence()
            .filter { it.startsWith("source:") }
            .mapNotNull { key ->
                val source = load(key) ?: return@mapNotNull null
                if (source.homeId != homeId) return@mapNotNull null
                StoredSourceInHome(key.removePrefix("source:"), source)
            }
            .sortedBy { it.storageId }
            .toList()

    /** Bind the EXACT persisted storage IDs and all fields into the UI snapshot. */
    private fun bindingDigest(sources: List<StoredSourceInHome>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateField(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf(
                (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        for (stored in sources) {
            val source = stored.anchor
            updateField(stored.storageId)
            updateField(source.sourceMichiId)
            updateField(source.homeId)
            updateField(source.authorityMichiId)
            updateField(source.authorityPublicKeyBase64Url)
            updateField(source.pinnedBaseUrl)
            updateField(source.tlsSpkiSha256)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun homeSnapshot(homeId: String): AirCatEnrolledHomeAuthority? {
        val root = anchorFor(homeId) ?: return null
        if (root.homeId != homeId || root.authorityMichiId != homeId) return null
        val sources = sourceRecordsForHome(homeId)
        // Parseable inconsistent records are counted for destructive revocation
        // but are never promoted to enrolled/authorized peers.
        return AirCatEnrolledHomeAuthority(
            homeId = homeId,
            authorityMichiId = root.authorityMichiId,
            authorityPublicKeyBase64Url = root.authorityPublicKeyBase64Url,
            sourceMichiIds = sources.map { it.storageId },
            sourceBindingsSha256 = bindingDigest(sources),
        )
    }

    /**
     * A57/A61: remove ONE exact Home root and every attributable local record
     * atomically in ONE encrypted preferences commit. A stale confirmation is
     * rejected if a child was enrolled/replaced, or if authority material changed.
     * No other Home root or source can be modified by this operation.
     * Rotation of the Michi root is a separate explicit fresh QR enrollment.
     */
    @Synchronized
    fun revokeHomeIfUnchanged(expected: AirCatEnrolledHomeAuthority): Boolean = synchronized(mutationLock) {
        val current = homeSnapshot(expected.homeId) ?: return false
        if (current != expected) return false
        val editor = prefs.edit()
            .putBoolean("revoked-home:" + expected.homeId, true)
            .remove("home:" + expected.homeId)
        for (id in current.sourceMichiIds) editor.remove("source:" + id)
        editor.commit()
    }

    /** Operator-initiated revocation; never triggered by network metadata. */
    @Synchronized
    fun revokeSource(sourceMichiId: String): Boolean = synchronized(mutationLock) {
        // A55: no success for a nonexistent or malformed record. Revoking a
        // source invalidates every in-flight continuity recheck immediately;
        // the shared root remains usable by other independently enrolled peers.
        if (!sourceMichiId.matches(Regex("[A-Za-z0-9_-]{43}")) ||
            anchorForSource(sourceMichiId) == null) return false
        return prefs.edit().remove("source:" + sourceMichiId).commit()
    }

    override fun anchorFor(homeId: String): AirCatHomeAnchor? =
        if (prefs.getBoolean("revoked-home:" + homeId, false)) null
        else load("home:" + homeId)?.takeIf { it.homeId == homeId }

    override fun anchorForSource(sourceMichiId: String): AirCatHomeAnchor? =
        load("source:" + sourceMichiId)?.takeIf { it.sourceMichiId == sourceMichiId &&
            !prefs.getBoolean("revoked-home:" + it.homeId, false) }

    private fun load(key: String): AirCatHomeAnchor? = runCatching {
        prefs.getString(key, null)?.let { json.decodeFromString<StoredAnchor>(it).toAnchor() }
    }.getOrNull()

    @kotlinx.serialization.Serializable
    private data class StoredAnchor(
        val homeId: String, val authorityMichiId: String,
        val authorityPublicKeyBase64Url: String, val sourceMichiId: String,
        val tlsSpkiSha256: String, val pinnedBaseUrl: String,
    ) {
        fun toAnchor() = AirCatHomeAnchor(homeId, authorityMichiId,
            authorityPublicKeyBase64Url, sourceMichiId, tlsSpkiSha256, pinnedBaseUrl)
    }
    private fun AirCatHomeAnchor.toRecord() = StoredAnchor(homeId, authorityMichiId,
        authorityPublicKeyBase64Url, sourceMichiId, tlsSpkiSha256, pinnedBaseUrl)
}
