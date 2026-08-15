package org.michimusic.link

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.michimusic.link.dto.PairingStrategy

class PairedDeviceRegistryTest {

    private lateinit var context: Context
    private lateinit var legacyTokenStore: TokenStore
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var registry: PairedDeviceRegistry

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        legacyTokenStore = mockk(relaxed = true)
        prefs = FakeSharedPreferences()
        
        // Prevent auto-migration in standard tests
        prefs.edit().putBoolean("migrated_from_tokenstore", true).apply()
        
        registry = PairedDeviceRegistry(context, legacyTokenStore, prefs)
    }

    @Test
    fun `saveDevice saves a device correctly`() {
        val device = PairedDevice(
            deviceId = "dev_1",
            deviceName = "My Device",
            deviceToken = "token_abc",
            serverId = "srv_uuid_123",
            michiId = "michi_id_xyz",
            roles = listOf("audio_receiver"),
            features = listOf("session", "heartbeat")
        )
        registry.saveDevice(device)
        
        val retrieved = registry.getDevice("dev_1")
        assertNotNull(retrieved)
        assertEquals("My Device", retrieved?.deviceName)
        assertEquals("token_abc", retrieved?.deviceToken)
        assertEquals("srv_uuid_123", retrieved?.serverId)
        assertEquals("michi_id_xyz", retrieved?.michiId)
        assertEquals(listOf("audio_receiver"), retrieved?.roles)
        assertEquals(listOf("session", "heartbeat"), retrieved?.features)
    }

    @Test
    fun `saveDevice updates existing device`() {
        val device = PairedDevice(deviceId = "dev_1", deviceName = "Old", deviceToken = "token")
        registry.saveDevice(device)
        
        val updatedDevice = device.copy(deviceName = "New")
        registry.saveDevice(updatedDevice)
        
        val all = registry.getAllDevices()
        assertEquals(1, all.size)
        assertEquals("New", all[0].deviceName)
    }

    @Test
    fun `removeDevice removes the correct device`() {
        registry.saveDevice(PairedDevice(deviceId = "dev_1", deviceToken = "t1"))
        registry.saveDevice(PairedDevice(deviceId = "dev_2", deviceToken = "t2"))
        
        registry.removeDevice("dev_1")
        
        assertNull(registry.getDevice("dev_1"))
        assertNotNull(registry.getDevice("dev_2"))
    }

    @Test
    fun `clearAll removes all devices`() {
        registry.saveDevice(PairedDevice(deviceId = "dev_1", deviceToken = "t1"))
        registry.clearAll()
        assertEquals(0, registry.getAllDevices().size)
    }

    @Test
    fun `migrates from legacy TokenStore if not migrated yet`() {
        // Reset prefs for migration test
        prefs = FakeSharedPreferences()
        
        every { legacyTokenStore.getServerDeviceId() } returns "legacy_id"
        every { legacyTokenStore.getDeviceToken() } returns "legacy_token"
        every { legacyTokenStore.getServerName() } returns "Legacy Node"
        every { legacyTokenStore.getServerAlias() } returns null
        every { legacyTokenStore.getAuthStrategy() } returns PairingStrategy.LEGACY
        
        // The registry runs migration on init of securePrefs
        registry = PairedDeviceRegistry(context, legacyTokenStore, prefs)
        
        val device = registry.getDevice("legacy_id")
        assertNotNull(device)
        assertEquals("legacy_token", device?.deviceToken)
        assertEquals("Legacy Node", device?.deviceName)
        assertEquals(true, prefs.getBoolean("migrated_from_tokenstore", false))
    }
}

class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = map

    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    inner class EditorImpl : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { changes[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) map.clear()
            for ((k, v) in changes) {
                if (v == null) map.remove(k) else map[k] = v
            }
        }
    }
}
