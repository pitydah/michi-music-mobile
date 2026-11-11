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

/** Creation of one user-requested subfolder only; never delete or rename media implicitly. */
sealed interface MusicFolderCreationResult {
    data class Created(val name: String) : MusicFolderCreationResult
    data object InvalidName : MusicFolderCreationResult
    data object PermissionDenied : MusicFolderCreationResult
    data object ProviderReadOnly : MusicFolderCreationResult
    data object AlreadyExists : MusicFolderCreationResult
    data class Failed(val message: String) : MusicFolderCreationResult
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

    /** Read permission is independent of explicit optional write capability. */
    fun hasWritePermission(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions
        .any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    /** Only called after a separate user action selecting this exact active tree. */
    fun takePersistableWritePermission(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        hasWritePermission(uri)
    } catch (_: SecurityException) {
        false
    }

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
                            albumArtist = metadata.albumArtist,
                            genre = metadata.genre,
                            duration = metadata.duration,
                            size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L,
                            format = sourceFileExtension(childName)
                                .ifBlank { mime.substringAfterLast('/') },
                            filepath = documentUri.toString(),
                            // `downloaded` means an app-managed sync copy. A SAF
                            // document is indexed directly from an authorized
                            // tree, not downloaded by Michi Sync.
                            downloaded = false,
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

    /**
     * Only creates ONE child inside the already-selected root. A provider's
     * FLAG_DIR_SUPPORTS_CREATE is required, in addition to a retained READ+WRITE
     * grant. Unknown or revoked capabilities fail closed. Never handles raw paths.
     * No background rescan: an empty folder cannot create music by itself.
     */
    suspend fun createRootSubfolder(
        treeUri: Uri,
        rawName: String,
    ): MusicFolderCreationResult = withContext(Dispatchers.IO) {
        val name = normalizedMusicFolderName(rawName)
            ?: return@withContext MusicFolderCreationResult.InvalidName
        if (!hasPermission(treeUri) || !hasWritePermission(treeUri)) {
            return@withContext MusicFolderCreationResult.PermissionDenied
        }
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: IllegalArgumentException) {
            return@withContext MusicFolderCreationResult.Failed("La carpeta guardada no es válida.")
        }
        val resolver = context.contentResolver
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        try {
            val writableDirectory = resolver.query(
                rootDocument,
                arrayOf(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS,
                ), null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                mimeIndex >= 0 && flagsIndex >= 0 && !cursor.isNull(flagsIndex) &&
                    cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR &&
                    (cursor.getInt(flagsIndex) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
            } ?: false
            if (!writableDirectory) return@withContext MusicFolderCreationResult.ProviderReadOnly

            // Duplicate-name prevention is local to this directory. The SAF provider
            // retains ultimate authority and might reject or rename a concurrent create.
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
            val names = resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            ) ?: return@withContext MusicFolderCreationResult.Failed(
                "No se pudo comprobar el contenido de la carpeta. No se creó nada.",
            )
            val exists = names.use { cursor ->
                val col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (col < 0) return@use true // Do not create if collision cannot be ruled out.
                var found = false
                while (cursor.moveToNext()) {
                    ensureActive()
                    if (cursor.getString(col)?.equals(name, ignoreCase = true) == true) {
                        found = true
                        break
                    }
                }
                found
            }
            if (exists) return@withContext MusicFolderCreationResult.AlreadyExists

            ensureActive()
            val created = DocumentsContract.createDocument(
                resolver, rootDocument, DocumentsContract.Document.MIME_TYPE_DIR, name,
            ) ?: return@withContext MusicFolderCreationResult.Failed(
                "El proveedor no pudo crear la carpeta.",
            )
            // Some providers normalize or suffix a name. Report its actual label.
            val actual = runCatching {
                resolver.query(
                    created,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null,
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: name
            MusicFolderCreationResult.Created(actual)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            MusicFolderCreationResult.PermissionDenied
        } catch (_: Exception) {
            MusicFolderCreationResult.Failed("El proveedor no permitió crear la carpeta.")
        }
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
        val albumArtist: String = "",
        val genre: String = "",
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
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    .orEmpty(),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
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
