package org.michimusic.sync

import android.content.Context
import android.content.SharedPreferences

class SecureTokenStore(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "michi_secure_tokens"
        private const val KEY_SERVER_DEVICE_ID = "server_device_id"
        private const val KEY_SERVER_ALIAS = "server_alias"
        private const val KEY_CLIENT_DEVICE_ID = "client_device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_PERMISSIONS = "permissions"
        private const val KEY_PAIRED_AT = "paired_at"
    }

    // TODO: Migrar a EncryptedSharedPreferences cuando se agregue
    // implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // al módulo sync-client. Por ahora usamos SharedPreferences estándar.
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveToken(
        serverDeviceId: String,
        serverAlias: String,
        clientDeviceId: String,
        deviceToken: String,
        refreshToken: String = "",
        permissions: List<String> = emptyList(),
    ) {
        prefs.edit()
            .putString(KEY_SERVER_DEVICE_ID, serverDeviceId)
            .putString(KEY_SERVER_ALIAS, serverAlias)
            .putString(KEY_CLIENT_DEVICE_ID, clientDeviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_PERMISSIONS, permissions.joinToString(","))
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    fun getServerDeviceId(): String? = prefs.getString(KEY_SERVER_DEVICE_ID, null)
    fun getServerAlias(): String? = prefs.getString(KEY_SERVER_ALIAS, null)
    fun getClientDeviceId(): String? = prefs.getString(KEY_CLIENT_DEVICE_ID, null)
    fun getPermissions(): List<String> =
        prefs.getString(KEY_PERMISSIONS, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    fun getPairedAt(): Long = prefs.getLong(KEY_PAIRED_AT, 0L)

    fun isPaired(): Boolean = getDeviceToken()?.isNotEmpty() == true

    fun clear() {
        prefs.edit().clear().apply()
    }
}
