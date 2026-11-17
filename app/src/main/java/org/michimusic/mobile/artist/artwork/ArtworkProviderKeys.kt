package org.michimusic.mobile.artist.artwork

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * fanart.tv hands out two different keys and its terms require both to be able to
 * travel together: the *project* key identifies this application, and the *personal*
 * key belongs to the person using it. The terms are explicit — the personal key "should
 * be sent in addition to your project key" — so the request carries `api-key` and, when
 * one was entered, `client-key` alongside it.
 *
 * Both live in EncryptedSharedPreferences. They are never written to a log, never
 * embedded in a URL (a query string ends up in traffic counters) and never persisted
 * anywhere else. Reads must happen off the main thread: creating the master key touches
 * the Android keystore.
 */
internal object ArtworkProviderKeys {
    private const val FILE = "michi-artwork-provider-keys"
    private const val PROJECT = "fanart_project_key"
    private const val PERSONAL = "fanart_personal_key"

    data class Fanart(val projectKey: String?, val personalKey: String?)

    fun fanart(context: Context): Fanart {
        val prefs = preferences(context) ?: return Fanart(null, null)
        return Fanart(
            projectKey = prefs.read(PROJECT),
            personalKey = prefs.read(PERSONAL),
        )
    }

    /** Returns false when the keystore is unavailable, so the caller can say so. */
    fun saveFanart(context: Context, projectKey: String, personalKey: String): Boolean {
        val prefs = preferences(context) ?: return false
        return prefs.edit()
            .putString(PROJECT, projectKey.trim())
            .putString(PERSONAL, personalKey.trim())
            .commit()
    }

    private fun SharedPreferences.read(key: String): String? =
        getString(key, null)?.trim()?.takeIf(String::isNotEmpty)

    private fun preferences(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Exception) {
        // A device with a broken keystore must degrade to "no key", never to a crash
        // and never to a plaintext fallback: the provider simply stays unavailable.
        null
    }
}
