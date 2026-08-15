package org.michimusic.link

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.link.dto.PairingStrategy

@Serializable
data class PairedDevice(
    val deviceId: String = "",
    val deviceName: String = "",
    val serviceType: String = "",
    val deviceToken: String,
    val refreshToken: String = "",
    val permissions: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val authStrategy: PairingStrategy = PairingStrategy.LEGACY,
    val tokenRefreshSupported: Boolean = false,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastUrl: String = "",
    val serverId: String = "",
    val michiId: String = "",
    val publicKey: String = "",
    val identityScheme: String = "ed25519-blake3-v1",
    val remoteServerId: String = "",
    val remoteMichiId: String = "",
    val remotePublicKey: String = "",
    val pairedClientDeviceId: String = "",
) {
    val effectiveServerId: String get() = remoteServerId.ifEmpty { serverId.ifEmpty { deviceId } }
    val effectiveMichiId: String get() = remoteMichiId.ifEmpty { michiId }
    val effectivePublicKey: String get() = remotePublicKey.ifEmpty { publicKey }
    val effectiveClientDeviceId: String get() = pairedClientDeviceId
}

class PairedDeviceRegistry(
    private val context: Context,
    private val legacyTokenStore: TokenStore,
    private val injectedPrefs: SharedPreferences? = null
) {

    companion object {
        private const val REGISTRY_PREFS_NAME = "michi_link_registry_secure"
        private const val DEVICES_KEY = "paired_devices"
        private const val MIGRATED_FROM_TOKENSTORE = "migrated_from_tokenstore"
    }

    private val json = Json { ignoreUnknownKeys = true }
    
    private val securePrefs: SharedPreferences by lazy {
        injectedPrefs?.also { migrateFromLegacyIfNeeded(it) } ?: run {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                REGISTRY_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { migrateFromLegacyIfNeeded(it) }
        }
    }

    private fun migrateFromLegacyIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATED_FROM_TOKENSTORE, false)) return
        
        val legacyServerId = legacyTokenStore.getServerDeviceId() ?: legacyTokenStore.getServerId()
        val legacyToken = legacyTokenStore.getDeviceToken()
        
        if (!legacyServerId.isNullOrEmpty() && !legacyToken.isNullOrEmpty()) {
            val device = PairedDevice(
                deviceId = legacyServerId,
                deviceName = legacyTokenStore.getServerAlias() ?: legacyTokenStore.getServerName() ?: "Michi Node",
                serviceType = legacyTokenStore.getService() ?: "",
                deviceToken = legacyToken,
                refreshToken = legacyTokenStore.getRefreshToken() ?: "",
                permissions = legacyTokenStore.getPermissions(),
                roles = legacyTokenStore.getRoles(),
                features = legacyTokenStore.getFeatures(),
                authStrategy = legacyTokenStore.getAuthStrategy(),
                tokenRefreshSupported = legacyTokenStore.getTokenRefreshSupported(),
                pairedAt = legacyTokenStore.getPairedAt(),
                lastUrl = legacyTokenStore.getServerUrl() ?: ""
            )
            val current = readDevices(prefs).filter { it.deviceId != device.deviceId }.toMutableList()
            current.add(device)
            writeDevices(current, prefs)
        }
        prefs.edit().putBoolean(MIGRATED_FROM_TOKENSTORE, true).apply()
    }

    private fun readDevices(prefs: SharedPreferences): List<PairedDevice> {
        val devicesJson = prefs.getString(DEVICES_KEY, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<PairedDevice>>(devicesJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeDevices(devices: List<PairedDevice>, prefs: SharedPreferences) {
        prefs.edit().putString(DEVICES_KEY, json.encodeToString(devices)).apply()
    }

    fun getAllDevices(): List<PairedDevice> {
        return readDevices(securePrefs)
    }

    fun getDevice(deviceId: String): PairedDevice? {
        return getAllDevices().find { 
            it.effectiveServerId == deviceId || it.deviceId == deviceId || (it.remoteMichiId.isNotEmpty() && it.remoteMichiId == deviceId)
        }
    }

    fun saveDevice(device: PairedDevice, prefs: SharedPreferences = securePrefs) {
        val targetKey = device.effectiveServerId
        val current = readDevices(prefs).filter { 
            it.effectiveServerId != targetKey && it.deviceId != device.deviceId 
        }.toMutableList()
        current.add(device)
        writeDevices(current, prefs)
    }

    fun removeDevice(deviceId: String) {
        val current = getAllDevices().filter { 
            it.effectiveServerId != deviceId && it.deviceId != deviceId && (it.remoteMichiId.isEmpty() || it.remoteMichiId != deviceId)
        }
        writeDevices(current, securePrefs)
    }

    fun clearAll() {
        securePrefs.edit().remove(DEVICES_KEY).apply()
    }
}
