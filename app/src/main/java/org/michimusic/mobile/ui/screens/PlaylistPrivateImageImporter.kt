package org.michimusic.mobile.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal enum class PlaylistImageImportFailure {
    UNSUPPORTED_TYPE,
    TOO_LARGE,
    INVALID_DIMENSIONS,
    UNDECODABLE,
    UNREADABLE,
    WRITE_FAILED,
}

internal sealed interface PlaylistImageImportResult {
    data class Success(
        val token: String,
        val mime: String,
        val width: Int,
        val height: Int,
    ) : PlaylistImageImportResult

    data class Failure(val reason: PlaylistImageImportFailure) : PlaylistImageImportResult
}

internal fun playlistImageImportErrorMessage(failure: PlaylistImageImportFailure): String =
    when (failure) {
        PlaylistImageImportFailure.UNSUPPORTED_TYPE -> "Elige una imagen JPG, PNG o WebP."
        PlaylistImageImportFailure.TOO_LARGE -> "La imagen supera el límite de 25 MB."
        PlaylistImageImportFailure.INVALID_DIMENSIONS -> "Las dimensiones de la imagen no son válidas."
        PlaylistImageImportFailure.UNDECODABLE -> "No pudimos leer esa imagen."
        PlaylistImageImportFailure.UNREADABLE -> "No pudimos abrir la imagen seleccionada."
        PlaylistImageImportFailure.WRITE_FAILED -> "No se pudo guardar la copia privada de la imagen."
    }

/**
 * Copies a picked image into app-private storage. The original document is
 * only read while the picker grant is valid; the playlist references the
 * private copy after this method returns. A failure never touches the image
 * currently referenced by the playlist.
 */
internal class PlaylistPrivateImageImporter(private val context: Context) {
    private val appContext = context.applicationContext

    fun importFromUri(
        playlistId: String,
        field: String,
        uri: Uri,
    ): PlaylistImageImportResult {
        if (playlistId.isBlank() || field !in setOf("cover", "banner") || uri.scheme != "content") {
            return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
        }
        val resolver = appContext.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val size = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    null
                }
            }
        }.getOrNull()
        return try {
            val stream = resolver.openInputStream(uri)
                ?: return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
            stream.use { importFromStream(playlistId, field, mime, size, it) }
        } catch (_: SecurityException) {
            PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
        } catch (_: IOException) {
            PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
        }
    }

    internal fun importFromStream(
        playlistId: String,
        field: String,
        declaredMime: String?,
        declaredSize: Long?,
        input: InputStream,
    ): PlaylistImageImportResult {
        if (playlistId.isBlank() || field !in setOf("cover", "banner")) {
            return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
        }
        if (declaredSize != null && declaredSize < 0L) {
            return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
        }
        if (declaredSize != null && declaredSize > PLAYLIST_PRIVATE_IMAGE_MAX_BYTES) {
            return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.TOO_LARGE)
        }
        val declaredType = declaredMime?.lowercase()
        if (declaredType != null &&
            (!declaredType.startsWith("image/") || declaredType == "image/svg+xml")
        ) {
            return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNSUPPORTED_TYPE)
        }
        val directory = importDirectory()
            ?: return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.WRITE_FAILED)
        var temp: File? = null
        var normalized: File? = null
        try {
            var working = File.createTempFile("playlist-import-", ".tmp", directory)
            temp = working
            var detectedFormat: PlaylistPrivateImageFormat? = null
            var total = 0L
            FileOutputStream(working).use { output ->
                val header = ByteArray(16)
                var headerLength = 0
                while (headerLength < header.size) {
                    val read = input.read(header, headerLength, header.size - headerLength)
                    if (read < 0) break
                    headerLength += read
                }
                if (headerLength <= 0) {
                    return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
                }
                val format = detectPlaylistPrivateImageFormat(header.copyOf(headerLength))
                    ?: return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNSUPPORTED_TYPE)
                detectedFormat = format
                output.write(header, 0, headerLength)
                var copied = headerLength.toLong()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > PLAYLIST_PRIVATE_IMAGE_MAX_BYTES) {
                        return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.TOO_LARGE)
                    }
                    output.write(buffer, 0, read)
                }
                if (copied <= 0L) {
                    return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
                }
                output.fd.sync()
                total = copied
            }
            val format = detectedFormat
                ?: return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNSUPPORTED_TYPE)
            if (total <= 0L || working.length() != total) {
                return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.WRITE_FAILED)
            }
            val bounds = decodeBounds(working)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNDECODABLE)
            }
            if (!playlistPrivateImageBoundsAllowed(bounds.outWidth, bounds.outHeight)) {
                return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.INVALID_DIMENSIONS)
            }
            if (!decodeProbe(working, bounds)) {
                return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNDECODABLE)
            }
            val orientation = readOrientation(working, format)
            val swapsDimensions = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
            if (orientation != ExifInterface.ORIENTATION_NORMAL &&
                orientation != ExifInterface.ORIENTATION_UNDEFINED
            ) {
                val normalizedFile = File.createTempFile("playlist-normalized-", ".tmp", directory)
                normalized = normalizedFile
                if (!normalizeOrientation(working, normalizedFile, orientation, format)) {
                    return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.WRITE_FAILED)
                }
                working.delete()
                working = normalizedFile
                temp = working
                normalized = null
            }
            val fileName = playlistPrivateImageName(
                playlistId = playlistId,
                field = field,
                extension = format.extension,
                nonce = UUID.randomUUID().toString().replace("-", "").take(20),
            )
            val destination = File(directory, fileName)
            if (destination.exists()) {
                return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.WRITE_FAILED)
            }
            if (!publishAtomically(working, destination)) {
                destination.delete()
                return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.WRITE_FAILED)
            }
            // The temp path is now the destination; never delete the published file
            // from the cleanup block.
            temp = null
            if (!destination.isFile || destination.length() <= 0L) {
                destination.delete()
                return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.WRITE_FAILED)
            }
            return PlaylistImageImportResult.Success(
                token = playlistPrivateImageToken(fileName),
                mime = format.mime,
                width = if (swapsDimensions) bounds.outHeight else bounds.outWidth,
                height = if (swapsDimensions) bounds.outWidth else bounds.outHeight,
            )
        } catch (_: IOException) {
            return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.WRITE_FAILED)
        } catch (_: SecurityException) {
            return PlaylistImageImportResult.Failure(PlaylistImageImportFailure.UNREADABLE)
        } finally {
            temp?.delete()
            normalized?.delete()
        }
    }

    /** Resolves only a valid token under this app's private artwork directory. */
    fun resolve(token: String?): File? {
        val fileName = playlistPrivateImageFileName(token) ?: return null
        val root = File(appContext.filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY)
        val raw = File(root, fileName)
        if (Files.isSymbolicLink(raw.toPath())) return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonical = runCatching { raw.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) return null
        if (!canonical.isFile) return null
        if (canonical.length() !in 1L..PLAYLIST_PRIVATE_IMAGE_MAX_BYTES) return null
        return canonical
    }

    fun loadableUri(token: String?): String? =
        resolve(token)?.let { Uri.fromFile(it).toString() }

    fun delete(token: String?): Boolean {
        val file = resolve(token) ?: return false
        return runCatching { file.delete() }.getOrDefault(false) || !file.exists()
    }

    fun importDirectory(): File? {
        val directory = File(appContext.filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return null
        return directory
    }

    private fun decodeBounds(file: File): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options
    }

    /** Header bounds plus one sampled decode proves real pixel data. */
    private fun decodeProbe(file: File, bounds: BitmapFactory.Options): Boolean {
        var sample = 1
        while (bounds.outWidth / sample > PLAYLIST_PRIVATE_IMAGE_NORMALIZED_DIMENSION ||
            bounds.outHeight / sample > PLAYLIST_PRIVATE_IMAGE_NORMALIZED_DIMENSION
        ) {
            sample *= 2
        }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull() ?: return false
        return try {
            bitmap.width > 0 && bitmap.height > 0
        } finally {
            bitmap.recycle()
        }
    }

    private fun readOrientation(file: File, format: PlaylistPrivateImageFormat): Int {
        if (format != PlaylistPrivateImageFormat.JPEG) return ExifInterface.ORIENTATION_NORMAL
        return runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun normalizeOrientation(
        source: File,
        destination: File,
        orientation: Int,
        format: PlaylistPrivateImageFormat,
    ): Boolean {
        val bounds = decodeBounds(source)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / sample > PLAYLIST_PRIVATE_IMAGE_NORMALIZED_DIMENSION ||
            bounds.outHeight / sample > PLAYLIST_PRIVATE_IMAGE_NORMALIZED_DIMENSION
        ) {
            sample *= 2
        }
        val decoded = runCatching {
            BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull() ?: return false
        val matrix = orientationMatrix(orientation)
        val transformed = try {
            if (matrix.isIdentity) decoded else Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true,
            )
        } catch (_: IllegalArgumentException) {
            decoded.recycle()
            return false
        }
        return try {
            FileOutputStream(destination).use { output ->
                val compressFormat = when (format) {
                    PlaylistPrivateImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    PlaylistPrivateImageFormat.PNG -> Bitmap.CompressFormat.PNG
                    PlaylistPrivateImageFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSLESS
                }
                val quality = if (format == PlaylistPrivateImageFormat.JPEG) 92 else 100
                if (!transformed.compress(compressFormat, quality, output)) return false
                output.fd.sync()
            }
            destination.isFile && destination.length() > 0L
        } finally {
            if (transformed !== decoded) transformed.recycle()
            decoded.recycle()
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
        }
    }

    private fun publishAtomically(source: File, destination: File): Boolean {
        val atomic = runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrDefault(false)
        if (atomic) return true
        val moved = runCatching {
            Files.move(source.toPath(), destination.toPath())
            true
        }.getOrDefault(false)
        return moved || runCatching { source.renameTo(destination) }.getOrDefault(false)
    }
}
