package org.michimusic.link.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// End-to-end coverage of MichiIdentity's real constructor through the real (Robolectric-
// shadowed) Keystore/EncryptedSharedPreferences stack - unlike QrPairingParserTest, which mocks
// MichiIdentity entirely. This confirms the normal (non-corrupted) path still behaves exactly
// as before this fix. Reproducing the corrupted-keyset failure itself through a real
// AndroidKeyStore turned out to be inconsistent across Robolectric SDK configurations in this
// project (the shadow that makes EncryptedSharedPreferences work is not the same one that
// implements Context.deleteSharedPreferences), so that scenario is instead covered where it can
// be tested reliably: IdentityStorageRecoveryTest exercises the retry-once/give-up
// orchestration in isolation (no Android dependency at all), and Ed25519KeyLoadingTest exercises
// the identity round-trip against a freshly-recovered/empty store.
@RunWith(RobolectricTestRunner::class)
class MichiIdentityTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun freshInstall_generatesAValidIdentity() {
        val identity = MichiIdentity(context)

        assertNotNull(identity.publicKeyBase64Url)
        assertTrue(identity.publicKeyBase64Url.isNotBlank())
    }

    @Test
    fun existingStorage_preservesIdentityAcrossInstances() {
        val first = MichiIdentity(context)
        val firstPublicKey = first.publicKeyBase64Url

        val second = MichiIdentity(context)

        assertEquals(
            "a second instance backed by the same storage must be the same identity, not a new one",
            firstPublicKey,
            second.publicKeyBase64Url,
        )
    }
}
