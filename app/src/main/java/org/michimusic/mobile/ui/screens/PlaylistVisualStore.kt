package org.michimusic.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal data class PlaylistVisualState(
    val coverUri: String? = null,
    val bannerUri: String? = null,
    val pinned: Boolean = false,
    // Legacy key "focal" is still read as bannerFocalY when the new key is absent.
    val bannerFocalY: Float = 0.5f,
    val bannerFocalX: Float = 0.5f,
    val bannerScale: Float = 1f,
    val coverFocalX: Float = 0.5f,
    val coverFocalY: Float = 0.5f,
    val coverScale: Float = 1f,
)

internal sealed interface PlaylistVisualImageChange {
    data object Unchanged : PlaylistVisualImageChange
    data object Remove : PlaylistVisualImageChange
    data class Replace(val uri: Uri) : PlaylistVisualImageChange
}

/** One confirm applies both independent fields and their own focal/scale values. */
internal data class PlaylistVisualEditRequest(
    val cover: PlaylistVisualImageChange = PlaylistVisualImageChange.Unchanged,
    val banner: PlaylistVisualImageChange = PlaylistVisualImageChange.Unchanged,
    val coverFocalX: Float = 0.5f,
    val coverFocalY: Float = 0.5f,
    val coverScale: Float = 1f,
    val bannerFocalX: Float = 0.5f,
    val bannerFocalY: Float = 0.5f,
    val bannerScale: Float = 1f,
)

internal sealed interface PlaylistVisualEditResult {
    data object Success : PlaylistVisualEditResult
    data class Rejected(
        val field: String,
        val reason: PlaylistImageImportFailure,
    ) : PlaylistVisualEditResult
}

/**
 * Per-playlist UI metadata stored alongside existing user preferences, never in
 * the playlist's track IDs or external synced playlist schema.
 *
 * New selections are copied into app-private storage and referenced by a stable
 * private token. Existing granted content:// images remain readable for
 * backwards compatibility; no new broad gallery permission is requested.
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
    private val importer = PlaylistPrivateImageImporter(appContext)
    private fun invalidate() { _revision.value = _revision.value + 1L }
    private val prefs by lazy { appContext.getSharedPreferences("michi_settings", Context.MODE_PRIVATE) }

    private fun grantedImageUris(): Set<Uri> = runCatching {
        appContext.contentResolver.persistedUriPermissions.asSequence()
            .filter { it.isReadPermission }.map { it.uri }.toSet()
    }.getOrDefault(emptySet())

    private fun verifiedContentImage(uriString: String?, grantedUris: Set<Uri>): String? {
        if (uriString.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return uriString.takeIf { uri.scheme == "content" && uri in grantedUris }
    }

    /**
     * A stored content URI is accepted only while its persisted grant exists.
     * A stored private token is accepted only after it resolves to the app's
     * own private directory, and is exposed as a loadable file:// URI.
     */
    private fun resolveStoredImage(
        stored: String?,
        grantedUris: Set<Uri> = grantedImageUris(),
    ): String? {
        if (stored.isNullOrBlank()) return null
        return if (playlistPrivateImageFileName(stored) != null) {
            importer.loadableUri(stored)
        } else {
            verifiedContentImage(stored, grantedUris)
        }
    }

    private fun readFloat(key: String, fallback: Float): Float =
        runCatching { prefs.getFloat(key, fallback) }
            .getOrElse { runCatching { prefs.getString(key, null)?.toFloatOrNull() }.getOrNull() ?: fallback }

    private fun readNow(id: String, grantedUris: Set<Uri> = grantedImageUris()): PlaylistVisualState {
        // Old versions wrote only "focal" (banner Y). Keep reading it unless the
        // independently persisted banner_focal_y key exists.
        val legacyFocal = readFloat(playlistVisualKey(id, "focal"), 0.5f)
        return PlaylistVisualState(
            coverUri = resolveStoredImage(
                runCatching { prefs.getString(playlistVisualKey(id, "cover"), null) }.getOrNull(),
                grantedUris,
            ),
            bannerUri = resolveStoredImage(
                runCatching { prefs.getString(playlistVisualKey(id, "banner"), null) }.getOrNull(),
                grantedUris,
            ),
            pinned = runCatching { prefs.getBoolean(playlistVisualKey(id, "pinned"), false) }
                .getOrDefault(false),
            bannerFocalY = safePlaylistBannerFocal(
                readFloat(playlistVisualKey(id, "banner_focal_y"), legacyFocal),
            ),
            bannerFocalX = safePlaylistBannerFocal(
                readFloat(playlistVisualKey(id, "banner_focal_x"), 0.5f),
            ),
            bannerScale = safePlaylistScale(
                readFloat(playlistVisualKey(id, "banner_scale"), 1f),
            ),
            coverFocalX = safePlaylistBannerFocal(
                readFloat(playlistVisualKey(id, "cover_focal_x"), 0.5f),
            ),
            coverFocalY = safePlaylistBannerFocal(
                readFloat(playlistVisualKey(id, "cover_focal_y"), 0.5f),
            ),
            coverScale = safePlaylistScale(
                readFloat(playlistVisualKey(id, "cover_scale"), 1f),
            ),
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
     * Imports a picker selection as a private copy, then references it. The old
     * image is untouched unless both the copy and the preference write succeed.
     */
    suspend fun importPickedImage(
        id: String,
        field: String,
        uri: Uri,
    ): PlaylistImageImportResult = withContext(io) {
        require(field == "cover" || field == "banner")
        if (id.isBlank()) {
            return@withContext PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
        }
        val imported = importer.importFromUri(id, field, uri)
        if (imported !is PlaylistImageImportResult.Success) return@withContext imported
        synchronized(mutationLock) {
            val key = playlistVisualKey(id, field)
            val old = prefs.getString(key, null)
            // One atomic persistent write. Commit runs on IO under the shared
            // cross-screen lock.
            if (!prefs.edit().putString(key, imported.token).commit()) {
                importer.delete(imported.token)
                return@synchronized PlaylistImageImportResult.Failure(
                    PlaylistImageImportFailure.WRITE_FAILED,
                )
            }
            cleanupReplacedImage(id, old)
            invalidate()
            imported
        }
    }

    /** Boolean compatibility shim for the existing three-dot shortcuts. */
    suspend fun savePickedImage(id: String, field: String, uri: Uri): Boolean =
        importPickedImage(id, field, uri) is PlaylistImageImportResult.Success

    /**
     * Applies both fields, their focal points and their scales in one commit.
     * New images are imported first; any failure leaves the previous images and
     * metadata exactly as they were.
     */
    suspend fun applyVisualEdit(
        id: String,
        request: PlaylistVisualEditRequest,
    ): PlaylistVisualEditResult = withContext(io) {
        require(id.isNotBlank())
        val coverImport = when (val change = request.cover) {
            is PlaylistVisualImageChange.Replace ->
                importer.importFromUri(id, "cover", change.uri)
            else -> null
        }
        if (coverImport is PlaylistImageImportResult.Failure) {
            return@withContext PlaylistVisualEditResult.Rejected("cover", coverImport.reason)
        }
        val bannerImport = when (val change = request.banner) {
            is PlaylistVisualImageChange.Replace ->
                importer.importFromUri(id, "banner", change.uri)
            else -> null
        }
        if (bannerImport is PlaylistImageImportResult.Failure) {
            (coverImport as? PlaylistImageImportResult.Success)?.let { importer.delete(it.token) }
            return@withContext PlaylistVisualEditResult.Rejected("banner", bannerImport.reason)
        }
        synchronized(mutationLock) {
            val coverKey = playlistVisualKey(id, "cover")
            val bannerKey = playlistVisualKey(id, "banner")
            val oldCover = prefs.getString(coverKey, null)
            val oldBanner = prefs.getString(bannerKey, null)
            val editor = prefs.edit()
            applyImageChange(
                editor,
                coverKey,
                request.cover,
                (coverImport as? PlaylistImageImportResult.Success)?.token,
            )
            applyImageChange(
                editor,
                bannerKey,
                request.banner,
                (bannerImport as? PlaylistImageImportResult.Success)?.token,
            )
            editor.putFloat(
                playlistVisualKey(id, "cover_focal_x"),
                safePlaylistBannerFocal(request.coverFocalX),
            )
            editor.putFloat(
                playlistVisualKey(id, "cover_focal_y"),
                safePlaylistBannerFocal(request.coverFocalY),
            )
            editor.putFloat(
                playlistVisualKey(id, "cover_scale"),
                safePlaylistScale(request.coverScale),
            )
            editor.putFloat(
                playlistVisualKey(id, "banner_focal_x"),
                safePlaylistBannerFocal(request.bannerFocalX),
            )
            editor.putFloat(
                playlistVisualKey(id, "banner_focal_y"),
                safePlaylistBannerFocal(request.bannerFocalY),
            )
            editor.putFloat(
                playlistVisualKey(id, "banner_scale"),
                safePlaylistScale(request.bannerScale),
            )
            if (!editor.commit()) {
                (coverImport as? PlaylistImageImportResult.Success)?.let { importer.delete(it.token) }
                (bannerImport as? PlaylistImageImportResult.Success)?.let { importer.delete(it.token) }
                return@synchronized PlaylistVisualEditResult.Rejected(
                    "cover",
                    PlaylistImageImportFailure.WRITE_FAILED,
                )
            }
            cleanupReplacedImage(id, oldCover)
            cleanupReplacedImage(id, oldBanner)
            invalidate()
            PlaylistVisualEditResult.Success
        }
    }

    private fun applyImageChange(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        change: PlaylistVisualImageChange,
        importedToken: String?,
    ) {
        when (change) {
            is PlaylistVisualImageChange.Replace -> {
                if (importedToken != null) editor.putString(key, importedToken) else editor.remove(key)
            }
            PlaylistVisualImageChange.Remove -> editor.remove(key)
            PlaylistVisualImageChange.Unchanged -> Unit
        }
    }

    /** Delete a field without touching the other image or the playlist's tracks. */
    suspend fun saveImage(id: String, field: String, uri: String?): Boolean = withContext(io) {
        require(field == "cover" || field == "banner")
        // New selections must pass the private import path above, never store an
        // arbitrary URI string without validating its persisted permission.
        require(uri == null)
        synchronized(mutationLock) {
            val key = playlistVisualKey(id, field)
            val old = prefs.getString(key, null)
            if (prefs.edit().remove(key).commit()) {
                cleanupReplacedImage(id, old)
                invalidate()
                true
            } else {
                false
            }
        }
    }

    /**
     * Persist focal X/Y and scale for ONE field. Field keys are independent:
     * changing the cover never rewrites the banner adjustment (or vice versa).
     */
    suspend fun setVisualAdjustment(
        id: String,
        field: String,
        focalX: Float,
        focalY: Float,
        scale: Float,
    ): Boolean = withContext(io) {
        require(field == "cover" || field == "banner")
        synchronized(mutationLock) {
            val committed = prefs.edit()
                .putFloat(
                    playlistVisualKey(id, "${field}_focal_x"),
                    safePlaylistBannerFocal(focalX),
                )
                .putFloat(
                    playlistVisualKey(id, "${field}_focal_y"),
                    safePlaylistBannerFocal(focalY),
                )
                .putFloat(
                    playlistVisualKey(id, "${field}_scale"),
                    safePlaylistScale(scale),
                )
                .commit()
            if (committed) invalidate()
            committed
        }
    }

    /** Legacy API retained for callers and tests; writes the new banner Y key. */
    suspend fun setBannerFocal(id: String, focalY: Float) = withContext(io) {
        synchronized(mutationLock) {
            prefs.edit {
                putFloat(
                    playlistVisualKey(id, "banner_focal_y"),
                    safePlaylistBannerFocal(focalY),
                )
            }
            invalidate()
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) = withContext(io) {
        synchronized(mutationLock) {
            prefs.edit { putBoolean(playlistVisualKey(id, "pinned"), pinned) }
            invalidate()
        }
    }

    suspend fun clear(id: String): Boolean = withContext(io) {
        synchronized(mutationLock) {
            val old = listOf("cover", "banner").mapNotNull {
                prefs.getString(playlistVisualKey(id, it), null)
            }.distinct()
            val editor = prefs.edit()
            listOf(
                "cover", "banner", "pinned", "focal",
                "cover_focal_x", "cover_focal_y", "cover_scale",
                "banner_focal_x", "banner_focal_y", "banner_scale",
            ).forEach {
                editor.remove(playlistVisualKey(id, it))
            }
            if (editor.commit()) {
                old.forEach { cleanupReplacedImage(id, it) }
                invalidate()
                true
            } else {
                false
            }
        }
    }

    /**
     * Deletes only a private copy whose generated file name belongs to THIS
     * playlist and that is no longer referenced anywhere. Content:// sources
     * are never deleted; only a UI5-owned grant may be released.
     */
    private fun cleanupReplacedImage(id: String, stored: String?) {
        if (stored.isNullOrBlank()) return
        if (playlistPrivateImageFileName(stored) != null) {
            if (playlistPrivateImageOwnedBy(id, stored) && !hasStoredReference(stored)) {
                importer.delete(stored)
            }
        } else {
            releaseUnusedOwnedGrant(stored)
        }
    }

    private fun hasStoredReference(stored: String): Boolean = prefs.all.values.any { value ->
        when (value) {
            is String -> value == stored
            is Set<*> -> value.any { it == stored }
            else -> false
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
