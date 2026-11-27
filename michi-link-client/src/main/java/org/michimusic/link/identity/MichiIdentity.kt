package org.michimusic.link.identity

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

private const val IDENTITY_PREFS_FILE_NAME = "michi_identity_store"
private const val PRIVATE_KEY_PREF = "private_key"

internal fun loadOrGenerateEd25519Key(prefs: SharedPreferences): Ed25519PrivateKeyParameters {
    val privKeyBase64 = prefs.getString(PRIVATE_KEY_PREF, null)
    return if (privKeyBase64 != null) {
        val privBytes = Base64.decode(privKeyBase64, Base64.DEFAULT)
        Ed25519PrivateKeyParameters(privBytes, 0)
    } else {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val priv = keyPair.private as Ed25519PrivateKeyParameters
        prefs.edit()
            .putString(PRIVATE_KEY_PREF, Base64.encodeToString(priv.encoded, Base64.DEFAULT))
            .apply()
        priv
    }
}

enum class IdentityStatus {
    VALID,
    RECOVERED_NEW_KEY,
    UNRECOVERABLE,
}

class MichiIdentity(private val context: Context) : MichiSigner {

    private companion object {
        private const val TAG = "MichiIdentity"
    }

    private var privateKey: Ed25519PrivateKeyParameters? = null
    private var publicKey: Ed25519PublicKeyParameters? = null
    var identityStatus: IdentityStatus = IdentityStatus.VALID
        private set

    init {
        loadOrGenerateKeys()
    }

    private fun loadOrGenerateKeys() {
        val prefs = openIdentityPrefs()
        privateKey = loadOrGenerateEd25519Key(prefs)
        publicKey = privateKey!!.generatePublicKey()
    }

    private fun openIdentityPrefs(): SharedPreferences =
        IdentityStorageRecovery.openWithRecovery(
            deleteCorruptedStorage = {
                Log.w(TAG, "Encrypted identity store unreadable (Keystore/keyset mismatch) - " +
                    "deleting local identity storage and generating a new identity. Re-pairing will be required.")
                identityStatus = IdentityStatus.RECOVERED_NEW_KEY
                context.deleteSharedPreferences(IDENTITY_PREFS_FILE_NAME)
            },
            createStore = { createEncryptedPrefs() },
        )

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            IDENTITY_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override val publicKeyBase64Url: String
        get() = encodeBase64Url(publicKey!!.encoded)

    override val michiId: String
        get() = org.michimusic.link.home.MichiIdentityHash.deriveMichiId(publicKeyBase64Url)
            ?: throw IllegalStateException("BLAKE3 identity derivation failed")

    fun signChallenge(nonceBase64Url: String): String =
        signCanonicalPayload(decodeBase64Url(nonceBase64Url))

    override fun signCanonicalPayload(payload: ByteArray): String {
        require(payload.isNotEmpty()) { "canonical payload must not be empty" }
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(payload, 0, payload.size)
        return encodeBase64Url(signer.generateSignature())
    }

    /** Raw 32-byte Ed25519 public key; exported only for canonical derivation. */
    fun publicKeyBytes(): ByteArray = publicKey!!.encoded.copyOf()

    fun verifyServerIdentity(serverMichiId: String, serverPublicKeyBase64Url: String): Boolean =
        org.michimusic.link.home.MichiIdentityHash.deriveMichiId(serverPublicKeyBase64Url) == serverMichiId

    fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return encodeBase64Url(bytes)
    }

    private fun encodeBase64Url(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun decodeBase64Url(data: String): ByteArray {
        return Base64.decode(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
