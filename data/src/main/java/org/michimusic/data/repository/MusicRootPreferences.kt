package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.security.MessageDigest

/**
 * The local library has exactly one user-visible music root. The default root
 * is the primary storage's Music/ directory and includes every subdirectory.
 * A custom root is a persisted SAF tree and replaces the default completely;
 * imported single files outside that root never become library tracks.
 */
enum class MusicRootType { DEFAULT_MUSIC, CUSTOM_TREE }

data class MusicRoot(
    val type: MusicRootType = MusicRootType.DEFAULT_MUSIC,
    val treeUri: String? = null,
    val displayName: String = DEFAULT_DISPLAY_NAME,
    /** Stable hash of the provider + tree document id. Never an ephemeral row id. */
    val sourceId: String? = null,
) {
    val isDefault: Boolean get() = type == MusicRootType.DEFAULT_MUSIC

    val label: String
        get() = if (isDefault) DEFAULT_DISPLAY_NAME else displayName.ifBlank { CUSTOM_FALLBACK_NAME }

    companion object {
        const val DEFAULT_DISPLAY_NAME = "Music/"
        const val CUSTOM_FALLBACK_NAME = "Carpeta elegida"
        val Default = MusicRoot()
    }
}

/**
 * Legacy source used by the short-lived pre-LR-01 implementation. It is only
 * kept so an old index can be cleaned up; new roots always use [customRootSourceId].
 */
const val CUSTOM_ROOT_SOURCE_ID = "saf_root"

data class RootScanSummary(
    val atMillis: Long,
    val trackCount: Int,
    val error: String? = null,
    val sourceId: String? = null,
)

class MusicRootPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun currentRoot(): MusicRoot {
        val uri = preferences.getString(KEY_TREE_URI, null)
        if (uri.isNullOrBlank()) return MusicRoot.Default
        return MusicRoot(
            type = MusicRootType.CUSTOM_TREE,
            treeUri = uri,
            displayName = preferences.getString(KEY_TREE_NAME, null)
                ?.takeIf { it.isNotBlank() }
                ?: MusicRoot.CUSTOM_FALLBACK_NAME,
            sourceId = preferences.getString(KEY_TREE_SOURCE, null)
                ?.takeIf { it.isNotBlank() }
                ?: CUSTOM_ROOT_SOURCE_ID,
        )
    }

    /** Atomic because a root switch changes which catalog is authoritative. */
    fun setCustomRoot(
        uri: Uri,
        displayName: String,
        sourceId: String = customRootSourceId(uri),
    ): Boolean = preferences.edit()
        .putString(KEY_TREE_URI, uri.toString())
        .putString(KEY_TREE_NAME, normalizeTreeDisplayName(displayName))
        .putString(KEY_TREE_SOURCE, sourceId)
        .remove(LEGACY_INCLUDED_FOLDERS)
        .commit()

    /** Restores Music/ and removes any previous custom-tree selection. */
    fun restoreDefault(): Boolean = preferences.edit()
        .remove(KEY_TREE_URI)
        .remove(KEY_TREE_NAME)
        .remove(KEY_TREE_SOURCE)
        .remove(LEGACY_INCLUDED_FOLDERS)
        .commit()

    fun clear(): Boolean = restoreDefault()

    /** Persists an already-confirmed root, used for rollback and migration. */
    fun saveRoot(root: MusicRoot): Boolean = if (root.isDefault) {
        restoreDefault()
    } else {
        val treeUri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return false
        setCustomRoot(
            uri = treeUri,
            displayName = root.displayName,
            sourceId = root.sourceId ?: customRootSourceId(treeUri),
        )
    }

    fun lastScan(): RootScanSummary? {
        val at = preferences.getLong(KEY_LAST_SCAN_AT, 0L)
        if (at <= 0L) return null
        return RootScanSummary(
            atMillis = at,
            trackCount = preferences.getInt(KEY_LAST_SCAN_COUNT, 0),
            error = preferences.getString(KEY_LAST_SCAN_ERROR, null)?.takeIf { it.isNotBlank() },
            sourceId = preferences.getString(KEY_LAST_SCAN_SOURCE, null)?.takeIf { it.isNotBlank() },
        )
    }

    fun recordScan(
        trackCount: Int,
        error: String? = null,
        atMillis: Long = System.currentTimeMillis(),
        sourceId: String? = null,
    ): Boolean = preferences.edit()
        .putLong(KEY_LAST_SCAN_AT, atMillis)
        .putInt(KEY_LAST_SCAN_COUNT, trackCount.coerceAtLeast(0))
        .putString(KEY_LAST_SCAN_ERROR, error)
        .putString(KEY_LAST_SCAN_SOURCE, sourceId)
        .commit()

    private companion object {
        const val PREFERENCES_NAME = "michi_library"
        const val KEY_TREE_URI = "root_tree_uri_v2"
        const val KEY_TREE_NAME = "root_tree_name_v2"
        const val KEY_TREE_SOURCE = "root_tree_source_v3"
        const val KEY_LAST_SCAN_AT = "root_last_scan_at_v1"
        const val KEY_LAST_SCAN_COUNT = "root_last_scan_count_v1"
        const val KEY_LAST_SCAN_ERROR = "root_last_scan_error_v1"
        const val KEY_LAST_SCAN_SOURCE = "root_last_scan_source_v1"
        const val LEGACY_INCLUDED_FOLDERS = "included_folders_v1"
    }
}

fun normalizeTreeDisplayName(raw: String?): String {
    val clean = raw?.trim().orEmpty()
    return clean.ifBlank { MusicRoot.CUSTOM_FALLBACK_NAME }
}

/** MediaStore stores relative paths with a trailing slash and nested segments. */
fun isInsideMusicRoot(relativePath: String?): Boolean {
    val normalized = relativePath
        ?.replace('\\', '/')
        ?.trim()
        ?.trim('/')
        ?: return false
    if (normalized.isEmpty()) return false
    return normalized.equals("music", ignoreCase = true) ||
        normalized.startsWith("music/", ignoreCase = true)
}

fun mediaStoreMusicRootSelection(
    relativePathColumn: String = MediaStore.Audio.Media.RELATIVE_PATH,
): String = "$relativePathColumn LIKE ? OR $relativePathColumn = ?"

val MUSIC_ROOT_QUERY_ARGS = arrayOf("Music/%", "Music")

/** DocumentsContract is not consulted for the default MediaStore root. */
fun isSupportedAudioDocument(mimeType: String?, displayName: String?): Boolean {
    val mime = mimeType?.trim()?.lowercase().orEmpty()
    if (mime.startsWith("audio/")) return true
    if (mime == "application/ogg" || mime == "application/x-ogg") return true
    val extension = displayName
        ?.substringAfterLast('.', "")
        ?.trim()
        ?.lowercase()
        .orEmpty()
    return extension in AUDIO_EXTENSIONS
}

/**
 * A provider authority is part of the identity: the same document id can exist in
 * two different document providers. SHA-256 avoids the birthday/collision problem
 * of Kotlin's 32-bit hashCode while keeping the primary key compact.
 */
fun treeTrackId(treeUri: Uri, documentId: String): String {
    val authority = treeUri.authority?.lowercase().orEmpty()
    return "safroot_${sha256Hex("$authority\u0000$documentId").take(40)}"
}

/** Source scope for one selected tree; stable across reindexes of the same tree. */
fun customRootSourceId(treeUri: Uri): String {
    val authority = treeUri.authority?.lowercase().orEmpty()
    val treeDocumentId = runCatching {
        DocumentsContract.getTreeDocumentId(treeUri)
    }.getOrDefault(treeUri.toString())
    return "safroot_${sha256Hex("$authority\u0000$treeDocumentId").take(40)}"
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { "%02x".format(it) }

private val AUDIO_EXTENSIONS = setOf(
    "aac", "aif", "aiff", "alac", "ape", "flac", "m4a", "mka", "mp3", "mp4",
    "oga", "ogg", "opus", "wav", "wave", "wma", "wv",
)
