package org.michimusic.link.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Ed25519KeyLoadingTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun freshPrefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).apply { edit().clear().commit() }

    @Test
    fun coldStart_emptyStorage_generatesNewIdentityAndPersistsIt() {
        val prefs = freshPrefs("cold_start")

        val key = loadOrGenerateEd25519Key(prefs)

        assertNotNull(key)
        val privateKeyBase64 = prefs.getString("private_key", null)
        assertNotNull("private_key must be written to SharedPreferences", privateKeyBase64)
        assertFalse(privateKeyBase64!!.isBlank())
    }

    @Test
    fun subsequentCalls_againstSameStorage_returnIdenticalKey() {
        val prefs = freshPrefs("repeat_read")

        val first = loadOrGenerateEd25519Key(prefs)
        val second = loadOrGenerateEd25519Key(prefs)

        assertArrayEquals(
            "a second call against the same storage must return the same identity, not a new one",
            first.encoded,
            second.encoded,
        )
    }

    @Test
    fun afterRecovery_freshEmptyStorage_producesNewValidIdentity() {
        val staleIdentityPrefs = freshPrefs("recovered_identity")
        val oldKey = loadOrGenerateEd25519Key(staleIdentityPrefs)
        staleIdentityPrefs.edit().clear().apply()

        val newKey = loadOrGenerateEd25519Key(staleIdentityPrefs)

        assertNotNull(newKey)
        assertFalse(oldKey.encoded.contentEquals(newKey.encoded))
        val publicKey = newKey.generatePublicKey()
        assertNotNull(publicKey)
    }

    @Test
    fun generatedKey_isAUsableEd25519PrivateKey() {
        val prefs = freshPrefs("usable_key")

        val key: Ed25519PrivateKeyParameters = loadOrGenerateEd25519Key(prefs)

        assertNotNull(key.generatePublicKey())
    }
}
