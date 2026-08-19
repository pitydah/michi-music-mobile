package org.michimusic.link.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Exercises loadOrGenerateEd25519Key() - the identity round-trip logic behind MichiIdentity -
// against a plain, unencrypted SharedPreferences. No Android Keystore is involved, so this is
// exactly the scenario after a successful (first-run, or post-recovery) prefs open: given a
// working SharedPreferences, does the right key get read back or generated. Needs Robolectric
// only for android.util.Base64, not for any crypto/Keystore shadowing.
@RunWith(RobolectricTestRunner::class)
class Ed25519KeyLoadingTest {

    private fun freshPrefs(name: String) =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun firstRun_noStoredKey_generatesAndPersistsNewKey() {
        val prefs = freshPrefs("first_run")

        val key = loadOrGenerateEd25519Key(prefs)

        assertNotNull(key)
        assertNotNull("the generated key must be persisted for next time", prefs.getString("private_key", null))
    }

    @Test
    fun existingStoredKey_isPreservedAcrossCalls_notRegenerated() {
        val prefs = freshPrefs("existing_key")
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
        // Simulates the post-recovery state: the corrupted store was deleted, so
        // openIdentityPrefs() hands loadOrGenerateEd25519Key() a brand new, empty prefs file.
        val staleIdentityPrefs = freshPrefs("recovered_identity")
        val oldKey = loadOrGenerateEd25519Key(staleIdentityPrefs)
        staleIdentityPrefs.edit().clear().apply() // stand-in for deleteSharedPreferences()

        val newKey = loadOrGenerateEd25519Key(staleIdentityPrefs)

        assertNotNull(newKey)
        // A real new identity, not a leftover/blank one - and different from the pre-recovery key.
        assertFalse(oldKey.encoded.contentEquals(newKey.encoded))
        val publicKey = newKey.generatePublicKey()
        assertNotNull(publicKey)
    }

    @Test
    fun generatedKey_isAUsableEd25519PrivateKey() {
        val prefs = freshPrefs("usable_key")

        val key: Ed25519PrivateKeyParameters = loadOrGenerateEd25519Key(prefs)

        // 32 raw bytes is the Ed25519 private key size; a wrong/corrupt encoding would fail here.
        assertNotNull(key.generatePublicKey())
    }
}
