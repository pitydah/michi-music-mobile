package org.michimusic.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PlaylistVisualState(
    val coverUri: String? = null,
    val bannerUri: String? = null,
    val pinned: Boolean = false,
    val bannerFocalY: Float = 0.5f,
)

/**
 * Per-playlist UI metadata stored alongside existing user preferences, never in
 * the playlist's track IDs or external synced playlist schema. SAF grants are
 * required for every saved image: a temporary photo-picker URI is not persisted.
 */
internal class PlaylistVisualStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        // All store instances (Home, Catalog and Detail) observe one authoritative
        // revision. A change to artwork/pinning must not depend on playlist table Flow.
        private val mutationLock = Any()
        private val _revision = MutableStateFlow(0L)
        val revision: StateFlow<Long> = _revision.asStateFlow()
    }
    private val appContext = context.applicationContext
    private fun invalidate() { _revision.value = _revision.value + 1L }
    private val prefs by lazy { appContext.getSharedPreferences("michi_settings", Context.MODE_PRIVATE) }

    private fun grantedImageUris(): Set<Uri> = runCatching {
        appContext.contentResolver.persistedUriPermissions.asSequence()
            .filter { it.isReadPermission }.map { it.uri }.toSet()
    }.getOrDefault(emptySet())

    private fun verifiedImage(uriString: String?, grantedUris: Set<Uri> = grantedImageUris()): String? {
        if (uriString.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return uriString.takeIf { uri.scheme == "content" && uri in grantedUris }
    }

    private fun readNow(id: String, grantedUris: Set<Uri> = grantedImageUris()): PlaylistVisualState {
        val focal = runCatching { prefs.getFloat(playlistVisualKey(id, "focal"), 0.5f) }
            .getOrDefault(0.5f)
        return PlaylistVisualState(
            coverUri = verifiedImage(runCatching { prefs.getString(playlistVisualKey(id, "cover"), null) }.getOrNull(), grantedUris),
            bannerUri = verifiedImage(runCatching { prefs.getString(playlistVisualKey(id, "banner"), null) }.getOrNull(), grantedUris),
            pinned = runCatching { prefs.getBoolean(playlistVisualKey(id, "pinned"), false) }.getOrDefault(false),
            bannerFocalY = focal.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
        )
    }

    suspend fun read(id: String): PlaylistVisualState = withContext(io) { readNow(id) }

    /** One IO turn for large catalogs: do not suspend and reopen disk per row. */
    suspend fun readMany(ids: List<String>): Map<String, PlaylistVisualState> = withContext(io) {
        // One permission snapshot for the whole catalog, not 2 N resolver reads.
        val grantedUris = grantedImageUris()
        ids.asSequence().filter { it.isNotBlank() }.distinct()
            .associateWith { readNow(it, grantedUris) }
    }

    /**
     * Image picker selection is committed as ONE IO operation. Previously granted
     * document permissions from other subsystems are borrowed, never owned by UI5.
     * A newly persisted grant is tagged as UI5-owned before the URI is referenced.
     */
    suspend fun savePickedImage(id: String, field: String, uri: Uri): Boolean = withContext(io) {
        require(field == "cover" || field == "banner")
        if (id.isBlank() || uri.scheme != "content") return@withContext false
        val resolver = appContext.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val bytes = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        }.getOrNull()
        if (!acceptablePlaylistImageMetadata(mime, bytes)) return@withContext false
        synchronized(mutationLock) {
            val existingGrant = uri in grantedImageUris()
            val ownKey = playlistOwnedGrantKey(uri.toString())
            if (!existingGrant) {
                if (runCatching { resolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ) }.isFailure) return@synchronized false
                if (uri !in grantedImageUris()) return@synchronized false
            }
            val fieldKey = playlistVisualKey(id, field)
            val oldUri = prefs.getString(fieldKey, null)
            // One atomic persistent write, not two apply() calls separated by a crash
            // window. Commit runs on IO under the shared cross-screen lock.
            val editor = prefs.edit().putString(fieldKey, uri.toString())
            if (!existingGrant) editor.putString(ownKey, uri.toString())
            if (!editor.commit()) {
                if (!existingGrant) runCatching {
                    resolver.releasePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                return@synchronized false
            }
            oldUri?.takeIf { it != uri.toString() }?.let(::releaseUnusedOwnedGrant)
            invalidate()
            true
        }
    }

    /** Delete a field without touching the other image or the playlist's tracks. */
    suspend fun saveImage(id: String, field: String, uri: String?) = withContext(io) {
        require(field == "cover" || field == "banner")
        // New selections must pass the atomic picker path above, never store an
        // arbitrary content:// string without validating its persisted permission.
        require(uri == null)
        synchronized(mutationLock) {
            val key = playlistVisualKey(id, field)
            val oldUri = prefs.getString(key, null)
            if (prefs.edit().remove(key).commit()) {
                oldUri?.let(::releaseUnusedOwnedGrant)
                invalidate()
            }
        }
    }

    /**
     * Do not release a URI while it is referenced by any UI5 playlist, nor a
     * grant we did not acquire. We also respect string references from other
     * features in the same preferences file, if present.
     */
    private fun releaseUnusedOwnedGrant(uriString: String) {
        val ownKey = playlistOwnedGrantKey(uriString)
        if (prefs.getString(ownKey, null) != uriString) return
        val references = prefs.all.filterKeys { it != ownKey }.values.flatMap { value ->
            when (value) {
                is String -> listOf(value)
                is Set<*> -> value.filterIsInstance<String>()
                else -> emptyList()
            }
        }
        if (!playlistGrantReleaseAllowed(uriString, setOf(uriString), references)) return
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        // The provider can revoke access independently. Clear ownership only
        // when the grant was released or is no longer present.
        val released = uri !in grantedImageUris() || runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (released) prefs.edit { remove(ownKey) }
    }

    suspend fun setPinned(id: String, pinned: Boolean) = withContext(io) {
        synchronized(mutationLock) {
            prefs.edit { putBoolean(playlistVisualKey(id, "pinned"), pinned) }
            invalidate()
        }
    }

    suspend fun setBannerFocal(id: String, focalY: Float) = withContext(io) {
        synchronized(mutationLock) {
            prefs.edit { putFloat(playlistVisualKey(id, "focal"), safePlaylistBannerFocal(focalY)) }
            invalidate()
        }
    }

    suspend fun clear(id: String) = withContext(io) {
        synchronized(mutationLock) {
            val old = listOf("cover", "banner").mapNotNull {
                prefs.getString(playlistVisualKey(id, it), null)
            }.distinct()
            val editor = prefs.edit()
            listOf("cover", "banner", "pinned", "focal").forEach {
                editor.remove(playlistVisualKey(id, it))
            }
            if (editor.commit()) {
                old.forEach(::releaseUnusedOwnedGrant)
                invalidate()
            }
        }
    }

    suspend fun readDensity(): PlaylistTrackDensity = withContext(io) {
        runCatching { PlaylistTrackDensity.valueOf(prefs.getString("ui5_playlist_density", "COMPACT") ?: "COMPACT") }
            .getOrDefault(PlaylistTrackDensity.COMPACT)
    }

    suspend fun setDensity(density: PlaylistTrackDensity) = withContext(io) {
        synchronized(mutationLock) {
            prefs.edit { putString("ui5_playlist_density", density.name) }
            invalidate()
        }
    }

    suspend fun readCatalogSort(): PlaylistCatalogSort = withContext(io) {
        runCatching { PlaylistCatalogSort.valueOf(prefs.getString("ui5_playlist_sort", "NAME") ?: "NAME") }
            .getOrDefault(PlaylistCatalogSort.NAME)
    }

    suspend fun setCatalogSort(sort: PlaylistCatalogSort) = withContext(io) {
        synchronized(mutationLock) {
            prefs.edit { putString("ui5_playlist_sort", sort.name) }
            invalidate()
        }
    }
}
