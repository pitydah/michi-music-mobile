package org.michimusic.link

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.michimusic.link.dto.PairingStrategy

class TokenStore(private val context: Context) {

    companion object {
        private const val LEGACY_PREFS_NAME = "michi_link_tokens"
        private const val SECURE_PREFS_NAME = "michi_link_secure"
        private const val MIGRATED_KEY = "migrated_from_legacy"

        private const val KEY_SERVER_ID = "server_id"
        private const val KEY_SERVER_NAME = "server_name"
        private const val KEY_SERVICE = "service"
        private const val KEY_SERVER_DEVICE_ID = "server_device_id"
        private const val KEY_SERVER_ALIAS = "server_alias"
        private const val KEY_CLIENT_DEVICE_ID = "client_device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_PERMISSIONS = "permissions"
        private const val KEY_PAIRED_AT = "paired_at"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ROLES = "roles"
        private const val KEY_FEATURES = "features"
        private const val KEY_AUTH_STRATEGY = "auth_strategy"
        private const val KEY_TOKEN_REFRESH_SUPPORTED = "token_refresh_supported"
    }

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { migrateFromLegacyIfNeeded(it) }
    }

    private fun migrateFromLegacyIfNeeded(secure: SharedPreferences) {
        if (secure.getBoolean(MIGRATED_KEY, false)) return
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val token = legacy.getString(KEY_DEVICE_TOKEN, null)
        if (token.isNullOrEmpty()) return

        secure.edit()
            .putString(KEY_SERVER_ID, legacy.getString(KEY_SERVER_ID, null))
            .putString(KEY_SERVER_NAME, legacy.getString(KEY_SERVER_NAME, null))
            .putString(KEY_SERVICE, legacy.getString(KEY_SERVICE, null))
            .putString(KEY_SERVER_DEVICE_ID, legacy.getString(KEY_SERVER_DEVICE_ID, null))
            .putString(KEY_SERVER_ALIAS, legacy.getString(KEY_SERVER_ALIAS, null))
            .putString(KEY_CLIENT_DEVICE_ID, legacy.getString(KEY_CLIENT_DEVICE_ID, null))
            .putString(KEY_DEVICE_TOKEN, token)
            .putString(KEY_REFRESH_TOKEN, legacy.getString(KEY_REFRESH_TOKEN, null))
            .putString(KEY_PERMISSIONS, legacy.getString(KEY_PERMISSIONS, null))
            .putString(KEY_SERVER_URL, legacy.getString(KEY_SERVER_URL, null))
            .putString(KEY_ROLES, legacy.getString(KEY_ROLES, null))
            .putString(KEY_FEATURES, legacy.getString(KEY_FEATURES, null))
            .putString(KEY_AUTH_STRATEGY, legacy.getString(KEY_AUTH_STRATEGY, null))
            .putBoolean(KEY_TOKEN_REFRESH_SUPPORTED, legacy.getBoolean(KEY_TOKEN_REFRESH_SUPPORTED, false))
            .putLong(KEY_PAIRED_AT, legacy.getLong(KEY_PAIRED_AT, 0L))
            .putBoolean(MIGRATED_KEY, true)
            .apply()

        legacy.edit().clear().apply()
    }

    fun save(
        serverId: String = "",
        serverName: String = "",
        service: String = "",
        serverDeviceId: String = "",
        serverAlias: String = "",
        clientDeviceId: String = "",
        deviceToken: String = "",
        refreshToken: String = "",
        permissions: List<String> = emptyList(),
        serverUrl: String = "",
        roles: List<String> = emptyList(),
        features: List<String> = emptyList(),
        authStrategy: PairingStrategy = PairingStrategy.LEGACY,
        tokenRefreshSupported: Boolean = false,
    ) {
        securePrefs.edit()
            .putString(KEY_SERVER_ID, serverId)
            .putString(KEY_SERVER_NAME, serverName)
            .putString(KEY_SERVICE, service)
            .putString(KEY_SERVER_DEVICE_ID, serverDeviceId)
            .putString(KEY_SERVER_ALIAS, serverAlias)
            .putString(KEY_CLIENT_DEVICE_ID, clientDeviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_PERMISSIONS, permissions.joinToString(","))
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_ROLES, roles.joinToString(","))
            .putString(KEY_FEATURES, features.joinToString(","))
            .putString(KEY_AUTH_STRATEGY, authStrategy.name)
            .putBoolean(KEY_TOKEN_REFRESH_SUPPORTED, tokenRefreshSupported)
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getServerId(): String? = securePrefs.getString(KEY_SERVER_ID, null)
    fun getServerName(): String? = securePrefs.getString(KEY_SERVER_NAME, null)
    fun getService(): String? = securePrefs.getString(KEY_SERVICE, null)
    fun getDeviceToken(): String? = securePrefs.getString(KEY_DEVICE_TOKEN, null)
    fun getRefreshToken(): String? = securePrefs.getString(KEY_REFRESH_TOKEN, null)
    fun getServerDeviceId(): String? = securePrefs.getString(KEY_SERVER_DEVICE_ID, null)
    fun getServerAlias(): String? = securePrefs.getString(KEY_SERVER_ALIAS, null)
    fun getClientDeviceId(): String? = securePrefs.getString(KEY_CLIENT_DEVICE_ID, null)
    fun getServerUrl(): String? = securePrefs.getString(KEY_SERVER_URL, null)
    fun getRoles(): List<String> = securePrefs.getString(KEY_ROLES, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    fun getFeatures(): List<String> = securePrefs.getString(KEY_FEATURES, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    fun getAuthStrategy(): PairingStrategy {
        val name = securePrefs.getString(KEY_AUTH_STRATEGY, null) ?: return PairingStrategy.LEGACY
        return try { PairingStrategy.valueOf(name) } catch (_: Exception) { PairingStrategy.LEGACY }
    }
    fun getTokenRefreshSupported(): Boolean = securePrefs.getBoolean(KEY_TOKEN_REFRESH_SUPPORTED, false)
    fun getPermissions(): List<String> = securePrefs.getString(KEY_PERMISSIONS, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    fun getPairedAt(): Long = securePrefs.getLong(KEY_PAIRED_AT, 0L)
    fun isPaired(): Boolean = getDeviceToken()?.isNotEmpty() == true

    fun clear() {
        securePrefs.edit().clear().apply()
    }
}
