package org.michimusic.mobile.devices.home

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.link.home.HomeMembershipDto

/**
 * MIM-05/06 durable, Keystore-backed state for an authorized Home membership.
 *
 * It stores ONLY:
 *  - the pending enrollment request id + claim token (needed to resume polling), and
 *  - the root-signed membership certificate of THIS device.
 *
 * It never stores a `michi_home_*` session token: those are short-lived and stay
 * in memory in [org.michimusic.link.LinkClient]. The file is excluded from
 * Android backup/device transfer because its MasterKey is device-bound.
 */
class HomeMembershipStore(context: Context) : HomeMembershipPersistence {
    private companion object {
        const val PREFS = "michi_home_membership_secure"
        val mutationLock = Any()
    }

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = false }
    private val prefs by lazy {
        // A restored/corrupted MasterKey must fail closed to NOT_ENROLLED, not
        // crash the app. Losing this file only costs a fresh admin-approved
        // enrollment; it never fabricates trust.
        runCatching { createPrefs() }.getOrElse {
            appContext.deleteSharedPreferences(PREFS)
            createPrefs()
        }
    }

    private fun createPrefs(): SharedPreferences {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            appContext, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Serializable
    data class PendingEnrollment(
        @SerialName("request_id") val requestId: String,
        @SerialName("claim_token") val claimToken: String,
        @SerialName("home_id") val homeId: String,
        @SerialName("source_michi_id") val sourceMichiId: String,
        @SerialName("device_name") val deviceName: String,
        @SerialName("requested_roles") val requestedRoles: List<String>,
        @SerialName("expires_at") val expiresAt: String = "",
        @SerialName("created_at_millis") val createdAtMillis: Long,
    )

    @Serializable
    data class MembershipRecord(
        @SerialName("home_id") val homeId: String,
        @SerialName("source_michi_id") val sourceMichiId: String,
        val membership: HomeMembershipDto,
        @SerialName("stored_at_millis") val storedAtMillis: Long,
    )

    override fun savePending(pending: PendingEnrollment): Boolean = synchronized(mutationLock) {
        if (pending.requestId.isBlank() || pending.claimToken.isBlank() ||
            pending.claimToken.length !in 16..512) return false
        prefs.edit()
            .putString(pendingKey(pending.homeId), json.encodeToString(pending))
            .commit()
    }

    override fun loadPending(homeId: String): PendingEnrollment? = synchronized(mutationLock) {
        read<PendingEnrollment>(pendingKey(homeId))
    }

    override fun clearPending(homeId: String): Boolean = synchronized(mutationLock) {
        prefs.edit().remove(pendingKey(homeId)).commit()
    }

    override fun saveMembership(record: MembershipRecord): Boolean = synchronized(mutationLock) {
        if (record.homeId != record.membership.homeId) return false
        prefs.edit()
            .putString(membershipKey(record.homeId), json.encodeToString(record))
            .commit()
    }

    override fun loadMembership(homeId: String): MembershipRecord? = synchronized(mutationLock) {
        read<MembershipRecord>(membershipKey(homeId))?.takeIf { it.homeId == homeId && it.membership.homeId == homeId }
    }

    /** Explicit revocation/identity loss: never re-enroll silently. */
    override fun clearMembership(homeId: String): Boolean = synchronized(mutationLock) {
        prefs.edit()
            .remove(membershipKey(homeId))
            .remove(pendingKey(homeId))
            .commit()
    }

    fun clearAll(): Boolean = synchronized(mutationLock) {
        prefs.edit().clear().commit()
    }

    private inline fun <reified T> read(key: String): T? = runCatching {
        prefs.getString(key, null)?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    private fun membershipKey(homeId: String) = "membership:$homeId"
    private fun pendingKey(homeId: String) = "pending:$homeId"
}
