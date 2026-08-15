package org.michimusic.link.identity

import android.content.Context
import android.util.Base64
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

class MichiIdentity(private val context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "michi_identity_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private var privateKey: Ed25519PrivateKeyParameters? = null
    private var publicKey: Ed25519PublicKeyParameters? = null

    init {
        loadOrGenerateKeys()
    }

    private fun loadOrGenerateKeys() {
        val privKeyBase64 = prefs.getString("private_key", null)
        if (privKeyBase64 != null) {
            val privBytes = Base64.decode(privKeyBase64, Base64.DEFAULT)
            privateKey = Ed25519PrivateKeyParameters(privBytes, 0)
            publicKey = privateKey!!.generatePublicKey()
        } else {
            val generator = Ed25519KeyPairGenerator()
            generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair: AsymmetricCipherKeyPair = generator.generateKeyPair()
            
            privateKey = keyPair.private as Ed25519PrivateKeyParameters
            publicKey = keyPair.public as Ed25519PublicKeyParameters

            prefs.edit()
                .putString("private_key", Base64.encodeToString(privateKey!!.encoded, Base64.DEFAULT))
                .apply()
        }
    }

    val publicKeyBase64Url: String
        get() = encodeBase64Url(publicKey!!.encoded)

    val michiId: String
        get() = try {
            val digest = Class.forName("org.bouncycastle.crypto.digests.Blake3Digest").getConstructor(Int::class.java).newInstance(256)
            val pubBytes = publicKey!!.encoded
            val updateMethod = digest.javaClass.getMethod("update", ByteArray::class.java, Int::class.java, Int::class.java)
            updateMethod.invoke(digest, pubBytes, 0, pubBytes.size)
            val hash = ByteArray(32)
            val doFinalMethod = digest.javaClass.getMethod("doFinal", ByteArray::class.java, Int::class.java)
            doFinalMethod.invoke(digest, hash, 0)
            encodeBase64Url(hash)
        } catch (e: Exception) {
            throw IllegalStateException("BLAKE3 digest is not available. MichiIdentity requires BouncyCastle with BLAKE3 support.", e)
        }

    fun signChallenge(nonceBase64Url: String): String {
        val nonceBytes = decodeBase64Url(nonceBase64Url)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(nonceBytes, 0, nonceBytes.size)
        val signature = signer.generateSignature()
        return encodeBase64Url(signature)
    }

    fun verifyServerIdentity(serverMichiId: String, serverPublicKeyBase64Url: String): Boolean {
        return try {
            val pubBytes = decodeBase64Url(serverPublicKeyBase64Url)
            val digest = Class.forName("org.bouncycastle.crypto.digests.Blake3Digest").getConstructor(Int::class.java).newInstance(256)
            val updateMethod = digest.javaClass.getMethod("update", ByteArray::class.java, Int::class.java, Int::class.java)
            updateMethod.invoke(digest, pubBytes, 0, pubBytes.size)
            val hash = ByteArray(32)
            val doFinalMethod = digest.javaClass.getMethod("doFinal", ByteArray::class.java, Int::class.java)
            doFinalMethod.invoke(digest, hash, 0)
            val computedId = encodeBase64Url(hash)
            computedId == serverMichiId
        } catch (e: Exception) {
            false
        }
    }

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
