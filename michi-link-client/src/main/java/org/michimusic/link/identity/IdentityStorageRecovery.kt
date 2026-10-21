package org.michimusic.link.identity

import java.io.IOException
import java.security.GeneralSecurityException

// Orchestrates the recovery of a Keystore-backed encrypted store whose wrapped keyset has
// become undecryptable (e.g. AEADBadTagException/KeyStoreException after the underlying
// Android Keystore key was lost or replaced while the encrypted file survived, typically via
// an uninstall/reinstall or a data restore). Deliberately Android-free (no SharedPreferences/
// Keystore types) so the retry-once/give-up semantics can be unit tested on the plain JVM;
// MichiIdentity supplies the real EncryptedSharedPreferences creation and deletion as lambdas.
internal object IdentityStorageRecovery {

    // Attempts `createStore()`. If it fails with a security/keyset-corruption error, calls
    // `deleteCorruptedStorage()` exactly once and retries `createStore()` exactly once more.
    // A second failure - of any kind - is a real, unrecoverable error and propagates as-is;
    // this never loops or retries more than once.
    fun <T> openWithRecovery(
        deleteCorruptedStorage: () -> Unit,
        createStore: () -> T,
    ): T = try {
        createStore()
    } catch (e: GeneralSecurityException) {
        recover(deleteCorruptedStorage, createStore)
    } catch (e: IOException) {
        recover(deleteCorruptedStorage, createStore)
    }

    private fun <T> recover(deleteCorruptedStorage: () -> Unit, createStore: () -> T): T {
        deleteCorruptedStorage()
        return createStore()
    }
}
