package org.michimusic.mobile.library

import android.content.Context
import android.os.StatFs
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only, on-demand insight into Michi Link's app-private downloads.
 * These bytes are NOT the size/free space of a user-selected SAF tree.
 * No recursive scan, media index, cleanup, or background observer is started.
 */
data class LibraryStorageSnapshot(
    val appVolumeAvailableBytes: Long?,
    val appVolumeTotalBytes: Long?,
    val syncedMusicBytes: Long?,
    val syncedFileCount: Int?,
)

object LibraryStorageDiagnostics {
    suspend fun read(context: Context): LibraryStorageSnapshot = withContext(Dispatchers.IO) {
        val privateRoot = context.applicationContext.filesDir
        val volume = runCatching { StatFs(privateRoot.absolutePath) }.getOrNull()
        val syncDir = File(privateRoot, "synced_music")
        // A missing directory is genuinely empty; an unreadable directory is UNKNOWN.
        // Never follow a symlink or mistake a provider failure for zero bytes.
        val entries: Array<File>? = when {
            !syncDir.exists() -> emptyArray<File>()
            Files.isSymbolicLink(syncDir.toPath()) || !syncDir.isDirectory -> null
            else -> syncDir.listFiles()
        }
        var bytes = 0L
        var count = 0
        // The transfer manager stores its download files directly in synced_music.
        // Only inspect this app-owned, single-level directory; never enter symlinks.
        entries?.forEach { file ->
            if (!Files.isSymbolicLink(file.toPath()) && file.isFile) {
                count += 1
                val length = file.length().coerceAtLeast(0L)
                bytes = if (Long.MAX_VALUE - bytes < length) Long.MAX_VALUE else bytes + length
            }
        }
        LibraryStorageSnapshot(
            appVolumeAvailableBytes = runCatching { volume?.availableBytes }.getOrNull(),
            appVolumeTotalBytes = runCatching { volume?.totalBytes }.getOrNull(),
            syncedMusicBytes = if (entries == null) null else bytes,
            syncedFileCount = if (entries == null) null else count,
        )
    }
}
