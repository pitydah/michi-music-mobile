package org.michimusic.mobile.sync

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class SyncCredentialsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("michi_sync_creds", Context.MODE_PRIVATE)

    var clientDeviceId: String
        get() = prefs.getString(KEY_CLIENT_DEVICE_ID, null) ?: generateAndSaveId()
        set(value) = prefs.edit().putString(KEY_CLIENT_DEVICE_ID, value).apply()

    var serverDeviceId: String
        get() = prefs.getString(KEY_SERVER_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_DEVICE_ID, value).apply()

    var serverAlias: String
        get() = prefs.getString(KEY_SERVER_ALIAS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_ALIAS, value).apply()

    var sessionToken: String
        get() = prefs.getString(KEY_SESSION_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SESSION_TOKEN, value).apply()

    var lastBaseUrl: String
        get() = prefs.getString(KEY_LAST_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_BASE_URL, value).apply()

    var lastPairingTime: Long
        get() = prefs.getLong(KEY_LAST_PAIRING_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PAIRING_TIME, value).apply()

    val hasSavedSession: Boolean
        get() = sessionToken.isNotEmpty() && lastBaseUrl.isNotEmpty()

    fun saveFromSession(
        baseUrl: String,
        token: String,
        serverId: String,
        alias: String,
    ) {
        lastBaseUrl = baseUrl
        sessionToken = token
        serverDeviceId = serverId
        serverAlias = alias
        lastPairingTime = System.currentTimeMillis()
    }

    fun clear() {
        prefs.edit().clear().apply()
        clientDeviceId = generateAndSaveId()
    }

    private fun generateAndSaveId(): String {
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_CLIENT_DEVICE_ID, id).apply()
        return id
    }

    companion object {
        private const val KEY_CLIENT_DEVICE_ID = "client_device_id"
        private const val KEY_SERVER_DEVICE_ID = "server_device_id"
        private const val KEY_SERVER_ALIAS = "server_alias"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_LAST_BASE_URL = "last_base_url"
        private const val KEY_LAST_PAIRING_TIME = "last_pairing_time"
    }
}
