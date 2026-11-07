package org.michimusic.mobile.artist

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Cached historical facts are external documentation, NEVER MediaStore/Track metadata. */
@Serializable
private data class StoredAlbumHistory(
    val confirmedReleaseGroupMbid: String,
    val profile: AlbumHistoricalProfile,
    val storedAtMs: Long,
)

data class CachedAlbumHistory(val profile: AlbumHistoricalProfile, val stale: Boolean)

interface AlbumEnrichmentSelectionStore {
    suspend fun load(scopeKey: String): CachedAlbumHistory?
    suspend fun save(scopeKey: String, profile: AlbumHistoricalProfile)
    suspend fun clear(scopeKey: String)
}

/** Separate namespace from Artist Enrichment; public facts only, no secrets or local audio. */
class PreferencesAlbumEnrichmentSelectionStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : AlbumEnrichmentSelectionStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        "michi-album-enrichment-v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(scopeKey: String): CachedAlbumHistory? = withContext(Dispatchers.IO) {
        if (!validKey(scopeKey)) return@withContext null
        try {
            val raw = prefs.getString(scopeKey, null) ?: return@withContext null
            val record = json.decodeFromString<StoredAlbumHistory>(raw)
            val now = clock()
            if (!albumHistoryCacheRecordValid(
                record.confirmedReleaseGroupMbid, record.profile, record.storedAtMs, now,
            )) {
                removeInvalidRecord(scopeKey)
                null
            } else CachedAlbumHistory(record.profile,
                stale = albumHistoryCacheIsStale(record.storedAtMs, now))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            removeInvalidRecord(scopeKey)
            null
        }
    }

    override suspend fun save(scopeKey: String, profile: AlbumHistoricalProfile) {
        require(validKey(scopeKey)) { "Clave de álbum fuera de biblioteca válida" }
        require(albumHistoryPayloadSafe(profile)) {
            "Perfil histórico sin identidad o fuente autorizadas"
        }
        withContext(Dispatchers.IO) {
            val now = clock()
            require(now >= 0L) { "Reloj inválido para la asociación histórica" }
            val record = StoredAlbumHistory(profile.mbid, profile, now)
            check(prefs.edit().putString(scopeKey, json.encodeToString(record)).commit()) {
                "No se pudo guardar la asociación histórica del álbum"
            }
        }
    }

    override suspend fun clear(scopeKey: String) {
        if (!validKey(scopeKey)) return
        withContext(Dispatchers.IO) {
            check(prefs.edit().remove(scopeKey).commit()) {
                "No se pudo eliminar la asociación histórica del álbum"
            }
        }
    }

    /** A malformed cache entry must not poison future reads indefinitely. */
    private fun removeInvalidRecord(scopeKey: String) {
        // Best effort only: a failed cleanup must not block on-demand discovery.
        runCatching { prefs.edit().remove(scopeKey).commit() }
    }

    private fun validKey(key: String) = key.matches(Regex("^[0-9a-f]{64}$"))
}

/** Stored separately from the ArtistEnrichmentCacheDatabase and never modifies that store. */
object AlbumEnrichmentPersistence {
    @Volatile private var instance: AlbumEnrichmentSelectionStore? = null
    fun from(context: Context): AlbumEnrichmentSelectionStore = instance ?: synchronized(this) {
        instance ?: PreferencesAlbumEnrichmentSelectionStore(context).also { instance = it }
    }
}
