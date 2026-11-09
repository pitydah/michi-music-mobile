package org.michimusic.data.repository

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao

/** Explicit outcome so a revoked grant is never reported as an empty library. */
sealed interface TreeIndexResult {
    data class Success(
        val treeUri: Uri,
        val sourceId: String,
        val displayName: String,
        val trackCount: Int,
    ) : TreeIndexResult

    data object PermissionDenied : TreeIndexResult
    data class Failed(val message: String) : TreeIndexResult
}

class SafMediaRepository(
    private val context: Context,
    private val trackDao: TrackDao,
    private val localMediaRepository: LocalMediaRepository? = null,
) {

    fun takePersistablePermission(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (_: SecurityException) {
        false
    }

    fun hasPermission(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions
        .any { it.uri == uri && it.isReadPermission }

    /** Validates a directory before a root is persisted. It does not index or persist a grant. */
    fun prepareSelection(treeUri: Uri): String? = readableDisplayNameOrNull(treeUri)

    /**
     * Recursively enumerates a SAF tree and replaces only its own source rows.
     * Rows are written only after the full traversal succeeds, and the Room
     * delete+insert is transactional. A failure or cancellation therefore leaves
     * the previously confirmed catalog untouched.
     */
    suspend fun indexTree(
        treeUri: Uri,
        onProgress: (Int) -> Unit = {},
    ): TreeIndexResult = withContext(Dispatchers.IO) {
        if (!hasPermission(treeUri) && !takePersistablePermission(treeUri)) {
            return@withContext TreeIndexResult.PermissionDenied
        }
        val sourceId = customRootSourceId(treeUri)
        val displayName = readableDisplayNameOrNull(treeUri) ?: MusicRoot.CUSTOM_FALLBACK_NAME
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: IllegalArgumentException) {
            return@withContext TreeIndexResult.Failed("La carpeta elegida no es válida.")
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
            ensureActive()
            val directoryId = pendingDirs.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
            val cursor = try {
                context.contentResolver.query(childrenUri, projection, null, null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                return@withContext TreeIndexResult.PermissionDenied
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
                if (idCol < 0 || nameCol < 0 || mimeCol < 0) {
                    queryFailed = true
                    return@use
                }
                while (c.moveToNext()) {
                    ensureActive()
                    val documentId = c.getString(idCol) ?: continue
                    val childName = c.getString(nameCol).orEmpty()
                    val mime = c.getString(mimeCol).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (seenDocuments.add(documentId)) pendingDirs.add(documentId)
                        continue
                    }
                    if (!isSupportedAudioDocument(mime, childName)) continue
                    if (!seenDocuments.add(documentId)) continue
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val metadata = retrieverMetadata(documentUri)
                    val title = metadata.title.ifBlank {
                        childName.substringBeforeLast('.').ifBlank { childName }
                    }
                    indexed.add(
                        CachedTrack(
                            id = treeTrackId(treeUri, documentId),
                            title = title,
                            artist = metadata.artist,
                            album = metadata.album,
                            duration = metadata.duration,
                            size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L,
                            format = sourceFileExtension(childName)
                                .ifBlank { mime.substringAfterLast('/') },
                            filepath = documentUri.toString(),
                            downloaded = true,
                            sourceId = sourceId,
                        ),
                    )
                    if (indexed.size % 25 == 0) onProgress(indexed.size)
                }
            }
        }

        if (queryFailed) {
            return@withContext TreeIndexResult.Failed(
                "El proveedor no permitió leer toda la carpeta. La biblioteca no cambió.",
            )
        }
        val written = try {
            trackDao.replaceForSource(sourceId, indexed)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!written) {
            return@withContext TreeIndexResult.Failed(
                "No se pudo guardar el índice de la carpeta. La biblioteca no cambió.",
            )
        }
        localMediaRepository?.invalidateCache()
        onProgress(indexed.size)
        TreeIndexResult.Success(treeUri, sourceId, displayName, indexed.size)
    }

    /** Removes an inactive/staged source after a confirmed replacement or rollback. */
    suspend fun clearSource(sourceId: String) = withContext(Dispatchers.IO) {
        trackDao.deleteForSource(sourceId)
        localMediaRepository?.invalidateCache()
    }

    fun treeDisplayName(treeUri: Uri): String =
        readableDisplayNameOrNull(treeUri) ?: MusicRoot.CUSTOM_FALLBACK_NAME

    private fun readableDisplayNameOrNull(treeUri: Uri): String? {
        val documentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        } catch (_: IllegalArgumentException) {
            return null
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
            }?.let(::normalizeTreeDisplayName) ?: MusicRoot.CUSTOM_FALLBACK_NAME
        } catch (_: SecurityException) {
            null
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
