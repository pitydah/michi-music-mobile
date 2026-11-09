package org.michimusic.data.repository

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao

class SafMediaRepository(
    private val context: Context,
    private val trackDao: TrackDao,
    private val localMediaRepository: LocalMediaRepository? = null,
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
     * Indexes every supported audio document below a user-selected tree. The tree
     * grant is persisted before any row is written; a failed grant leaves the
     * previous index untouched.
     */
    suspend fun indexTree(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        if (!hasPermission(treeUri) && !takePersistablePermission(treeUri)) return@withContext -1
        val resolver = context.contentResolver
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: IllegalArgumentException) {
            return@withContext -1
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val pendingDirs = ArrayDeque<String>()
        pendingDirs.add(rootDocumentId)
        val seenDocuments = HashSet<String>()
        val indexed = mutableListOf<CachedTrack>()
        var queryFailed = false

        while (pendingDirs.isNotEmpty()) {
            val directoryId = pendingDirs.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
            val cursor = try {
                resolver.query(childrenUri, projection, null, null, null)
            } catch (_: Exception) {
                queryFailed = true
                null
            }
            if (cursor == null) {
                queryFailed = true
                continue
            }
            cursor.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idCol < 0 || nameCol < 0 || mimeCol < 0) return@use
                while (c.moveToNext()) {
                    val documentId = c.getString(idCol) ?: continue
                    val displayName = c.getString(nameCol).orEmpty()
                    val mime = c.getString(mimeCol).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (seenDocuments.add(documentId)) pendingDirs.add(documentId)
                        continue
                    }
                    if (!isSupportedAudioDocument(mime, displayName)) continue
                    if (!seenDocuments.add(documentId)) continue
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val metadata = retrieverMetadata(documentUri)
                    val title = metadata.title.ifBlank {
                        displayName.substringBeforeLast('.').ifBlank { displayName }
                    }
                    indexed.add(
                        CachedTrack(
                            id = treeTrackId(documentId),
                            title = title,
                            artist = metadata.artist,
                            album = metadata.album,
                            duration = metadata.duration,
                            size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L,
                            format = sourceFileExtension(displayName)
                                .ifBlank { mime.substringAfterLast('/') },
                            filepath = documentUri.toString(),
                            downloaded = true,
                            sourceId = CUSTOM_ROOT_SOURCE_ID,
                        ),
                    )
                }
            }
        }

        if (queryFailed) return@withContext -1
        trackDao.deleteForSource(CUSTOM_ROOT_SOURCE_ID)
        if (indexed.isNotEmpty()) trackDao.insertAll(indexed)
        localMediaRepository?.invalidateCache()
        indexed.size
    }

    suspend fun clearIndexedTree() = withContext(Dispatchers.IO) {
        trackDao.deleteForSource(CUSTOM_ROOT_SOURCE_ID)
        localMediaRepository?.invalidateCache()
    }

    fun treeDisplayName(treeUri: Uri): String {
        val documentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        } catch (_: IllegalArgumentException) {
            return MusicRoot.CUSTOM_FALLBACK_NAME
        }
        return try {
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.let(::normalizeTreeDisplayName)
                ?: MusicRoot.CUSTOM_FALLBACK_NAME
        } catch (_: Exception) {
            MusicRoot.CUSTOM_FALLBACK_NAME
        }
    }

    private data class TreeMetadata(
        val title: String = "",
        val artist: String = "Unknown Artist",
        val album: String = "Unknown Album",
        val duration: Long = 0L,
    )

    private fun retrieverMetadata(uri: Uri): TreeMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            TreeMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() } ?: "Unknown Album",
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            )
        } catch (_: Exception) {
            TreeMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

}
