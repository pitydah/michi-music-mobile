package org.michimusic.data.repository

import android.content.Context
import java.util.Locale

/** One selectable top-level audio folder shown in Settings. */
data class MediaFolder(
    val name: String,
    val trackCount: Int,
    val musicTrackCount: Int,
)

/**
 * Persists the user's library-folder scope. No stored value means the smart
 * default: folders MediaStore tags as music, plus the conventional Music and
 * Download folders. The wildcard only represents an explicit "all folders"
 * choice; it is never the implicit default.
 */
class MusicFolderPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun includedFolders(): Set<String>? {
        val raw = preferences.getString(KEY_INCLUDED_FOLDERS, null) ?: return null
        val folders = raw.lineSequence()
            .mapNotNull(::normalizeFolderName)
            .toSet()
        return folders.takeIf { it.isNotEmpty() }
    }

    fun setIncludedFolders(folders: Set<String>) {
        val normalized = folders.mapNotNull(::normalizeFolderName).toSet()
        if (normalized.isEmpty()) {
            clearSelection()
            return
        }
        // apply() updates the in-memory map synchronously, so the next library
        // reload already observes the new scope.
        preferences.edit().putString(KEY_INCLUDED_FOLDERS, normalized.sorted().joinToString("\n")).apply()
    }

    fun clearSelection() {
        preferences.edit().remove(KEY_INCLUDED_FOLDERS).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "michi_library"
        const val KEY_INCLUDED_FOLDERS = "included_folders_v1"
    }
}

/** Normalizes a stored/display folder while preserving the explicit all-folders wildcard. */
fun normalizeFolderName(raw: String?): String? {
    val value = raw?.trim().orEmpty().trim('/', '\\').lowercase(Locale.ROOT)
    return value.takeIf { it.isNotEmpty() }
}

/**
 * Top-level folder for a MediaStore audio row. RELATIVE_PATH is normally
 * "Music/Album/", while BUCKET_DISPLAY_NAME is the fallback for providers that
 * omit it. Grouping by top level keeps the control small and lets a selected
 * "Music" folder cover every album beneath it.
 */
fun topLevelFolder(relativePath: String?, bucketDisplayName: String?): String? {
    val normalizedPath = relativePath.orEmpty()
        .replace('\\', '/')
        .trim()
        .trim('/')
    val firstSegment = normalizedPath.substringBefore('/').trim()
    return normalizeFolderName(firstSegment)
        ?: normalizeFolderName(bucketDisplayName)
}

fun isFolderIncluded(
    relativePath: String?,
    bucketDisplayName: String?,
    selectedFolders: Set<String>,
): Boolean {
    val normalizedSelection = selectedFolders.mapNotNull(::normalizeFolderName).toSet()
    if (ALL_FOLDERS in normalizedSelection) return true
    val folder = topLevelFolder(relativePath, bucketDisplayName) ?: return false
    return folder in normalizedSelection
}

/**
 * Smart default: every folder MediaStore tags as music, plus Music and
 * Download for untagged songs. Only known folders are returned so the dialog
 * never presents a phantom selection.
 */
fun smartIncludedFolders(
    foldersWithMusic: Set<String>,
    knownFolders: Set<String>,
): Set<String> {
    val known = knownFolders.mapNotNull(::normalizeFolderName).toSet()
    return (foldersWithMusic.mapNotNull(::normalizeFolderName).toSet() + CONVENTIONAL_MUSIC_FOLDERS)
        .filter { it in known }
        .toSet()
}

/**
 * Concrete initial selection for the folder dialog. If there is no music
 * evidence at all, selecting every known folder keeps the dialog usable and
 * mirrors the repository's safe fallback instead of silently hiding the
 * whole library.
 */
fun defaultFolderSelection(folders: List<MediaFolder>): Set<String> {
    val known = folders.map { it.name }.toSet()
    val smart = smartIncludedFolders(
        foldersWithMusic = folders.filter { it.musicTrackCount > 0 }.map { it.name }.toSet(),
        knownFolders = known,
    )
    return smart.ifEmpty { known }
}

const val ALL_FOLDERS = "*"

private val CONVENTIONAL_MUSIC_FOLDERS = setOf("music", "download")
