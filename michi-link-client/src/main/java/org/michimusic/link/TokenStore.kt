package org.michimusic.link

import android.content.Context
import android.content.SharedPreferences
import org.michimusic.link.dto.PairingStrategy

class TokenStore(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "michi_link_tokens"
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

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun save(
        serverId: String = "",
        serverName: String = "",
        service: String = "",
        serverDeviceId: String,
        serverAlias: String,
        clientDeviceId: String,
        deviceToken: String,
        refreshToken: String = "",
        permissions: List<String> = emptyList(),
        serverUrl: String = "",
        roles: List<String> = emptyList(),
        features: List<String> = emptyList(),
        authStrategy: PairingStrategy = PairingStrategy.LEGACY,
        tokenRefreshSupported: Boolean = false,
    ) {
        prefs.edit()
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

    fun getServerId(): String? = prefs.getString(KEY_SERVER_ID, null)
    fun getServerName(): String? = prefs.getString(KEY_SERVER_NAME, null)
    fun getService(): String? = prefs.getString(KEY_SERVICE, null)
    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    fun getServerDeviceId(): String? = prefs.getString(KEY_SERVER_DEVICE_ID, null)
    fun getServerAlias(): String? = prefs.getString(KEY_SERVER_ALIAS, null)
    fun getClientDeviceId(): String? = prefs.getString(KEY_CLIENT_DEVICE_ID, null)
    fun getServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)
    fun getRoles(): List<String> =
        prefs.getString(KEY_ROLES, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    fun getFeatures(): List<String> =
        prefs.getString(KEY_FEATURES, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    fun getAuthStrategy(): PairingStrategy {
        val name = prefs.getString(KEY_AUTH_STRATEGY, null) ?: return PairingStrategy.LEGACY
        return try { PairingStrategy.valueOf(name) } catch (_: Exception) { PairingStrategy.LEGACY }
    }
    fun getTokenRefreshSupported(): Boolean = prefs.getBoolean(KEY_TOKEN_REFRESH_SUPPORTED, false)
    fun getPermissions(): List<String> =
        prefs.getString(KEY_PERMISSIONS, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    fun getPairedAt(): Long = prefs.getLong(KEY_PAIRED_AT, 0L)
    fun isPaired(): Boolean = getDeviceToken()?.isNotEmpty() == true

    fun clear() {
        prefs.edit().clear().apply()
    }
}
