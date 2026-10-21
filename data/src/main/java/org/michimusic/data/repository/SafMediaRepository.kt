package org.michimusic.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.local.ReplayGainReader

class SafMediaRepository(
    private val context: Context,
    private val trackDao: TrackDao,
) {

    /**
     * Persists URI permissions for a granted document URI, taking care of providers
     * that don't support persistable permissions.
     */
    fun takePersistablePermission(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Checks if we currently have persistent permission to access this URI.
     */
    fun hasPermission(uri: Uri): Boolean {
        val permissions = context.contentResolver.persistedUriPermissions
        return permissions.any { it.uri == uri && it.isReadPermission }
    }

    /**
     * Imports an audio file picked via OpenDocument into our track index.
     */
    suspend fun importAudioUri(uri: Uri): Track? = withContext(Dispatchers.IO) {
        takePersistablePermission(uri)

        val metadata = extractMetadata(context.contentResolver, uri) ?: return@withContext null
        val id = "saf_${uri.hashCode().toUInt()}"
        val rg = ReplayGainReader.read(context, uri.toString())

        val cachedTrack = CachedTrack(
            id = id,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            duration = metadata.duration,
            size = metadata.size,
            format = metadata.mimeType.substringAfterLast("/"),
            filepath = uri.toString(),
            downloaded = true,
        )

        trackDao.insert(cachedTrack)

        Track(
            id = id,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            duration = metadata.duration,
            filepath = uri.toString(),
            size = metadata.size,
            format = cachedTrack.format,
            source = TrackSource.LOCAL,
            replayGainTrack = rg.trackGain,
            replayGainAlbum = rg.albumGain,
        )
    }

    data class SafMetadata(
        val title: String,
        val artist: String = "Unknown Artist",
        val album: String = "Unknown Album",
        val size: Long = 0L,
        val duration: Long = 0L,
        val mimeType: String = "audio/*",
    )

    private fun extractMetadata(contentResolver: ContentResolver, uri: Uri): SafMetadata? {
        var displayName: String? = null
        var size = 0L

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }

        val rawName = displayName ?: uri.lastPathSegment?.substringAfterLast(":")?.substringAfterLast("/") ?: "Imported Track"
        val title = rawName.substringBeforeLast(".")
        val mime = contentResolver.getType(uri) ?: "audio/*"

        return SafMetadata(
            title = title,
            size = size,
            mimeType = mime,
        )
    }
}
