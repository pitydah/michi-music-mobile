package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

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

const val CUSTOM_ROOT_SOURCE_ID = "saf_root"

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
        )
    }

    /** Atomic because a root switch changes which catalog is authoritative. */
    fun setCustomRoot(uri: Uri, displayName: String): Boolean =
        preferences.edit()
            .putString(KEY_TREE_URI, uri.toString())
            .putString(KEY_TREE_NAME, normalizeTreeDisplayName(displayName))
            .remove(LEGACY_INCLUDED_FOLDERS)
            .commit()

    /** Restores Music/ and removes any previous custom-tree selection. */
    fun restoreDefault(): Boolean =
        preferences.edit()
            .remove(KEY_TREE_URI)
            .remove(KEY_TREE_NAME)
            .remove(LEGACY_INCLUDED_FOLDERS)
            .commit()

    fun clear(): Boolean = restoreDefault()

    private companion object {
        const val PREFERENCES_NAME = "michi_library"
        const val KEY_TREE_URI = "root_tree_uri_v2"
        const val KEY_TREE_NAME = "root_tree_name_v2"
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

fun treeTrackId(documentId: String): String =
    "safroot_${documentId.hashCode().toUInt()}"

private val AUDIO_EXTENSIONS = setOf(
    "aac", "aif", "aiff", "alac", "ape", "flac", "m4a", "mka", "mp3", "mp4",
    "oga", "ogg", "opus", "wav", "wave", "wma",
)
