package org.michimusic.mobile.artist.artwork

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.michimusic.mobile.network.MICHI_HTTP_USER_AGENT
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadedArtwork(
    val relativePath: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

class ArtworkCacheException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Downloads bytes, rejects non-raster or oversized payloads, and only then publishes a
 * content-addressed private file. A URL in Room is never treated as a downloaded image.
 */
class ArtworkDownloader(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun download(candidate: ArtistArtworkCandidate): DownloadedArtwork = withContext(Dispatchers.IO) {
        if (!allowedArtworkImageUrl(candidate.source, candidate.imageUrl)) {
            throw ArtworkCacheException("Origen de imagen no autorizado")
        }
        val endpoint = candidate.imageUrl.toHttpUrlOrNull()
            ?: throw ArtworkCacheException("URL de imagen no válida")
        if (isPrivateOrLoopbackAddress(endpoint.host)) {
            throw ArtworkCacheException("Destino de imagen no público")
        }
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        if (!directory.isDirectory) throw ArtworkCacheException("No se pudo preparar la caché de artwork")
        val temp = File.createTempFile("download-", ".tmp", directory)
        try {
            val response = execute(endpoint)
            response.use {
                if (!it.isSuccessful) throw ArtworkHttpException(it.code)
                if (it.request.url.host != endpoint.host || it.request.url.scheme != "https") {
                    throw ArtworkCacheException("Redirección de imagen no autorizada")
                }
                val headerType = it.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (headerType !in ALLOWED_MIME) throw ArtworkCacheException("Tipo de imagen no admitido")
                val body = it.body ?: throw ArtworkCacheException("Imagen vacía")
                if (body.contentLength() > MAX_IMAGE_BYTES) throw ArtworkCacheException("Imagen demasiado grande")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_IMAGE_BYTES) throw ArtworkCacheException("Imagen demasiado grande")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                if (total <= 0L) throw ArtworkCacheException("Imagen vacía")
                val bounds = decodeBounds(temp)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw ArtworkCacheException("Imagen no decodificable")
                }
                if (bounds.outWidth < 320 || bounds.outHeight < 320) {
                    throw ArtworkCacheException("Resolución insuficiente")
                }
                if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
                    throw ArtworkCacheException("Resolución excesiva")
                }
                decodeProbe(temp, bounds)
                val mime = bounds.outMimeType?.lowercase()
                    ?.takeIf { it in ALLOWED_MIME }
                    ?: headerType
                    ?: throw ArtworkCacheException("Tipo de imagen no verificable")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                val extension = when (mime) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val relative = "$DIRECTORY/$sha.$extension"
                val final = File(context.filesDir, relative)
                if (final.isFile && final.length() == total && sha256Of(final) == sha) {
                    temp.delete()
                    return@withContext DownloadedArtwork(relative, sha, bounds.outWidth, bounds.outHeight, mime)
                }
                if (!temp.renameTo(final)) {
                    temp.copyTo(final, overwrite = true)
                    temp.delete()
                }
                if (!final.isFile || sha256Of(final) != sha) {
                    final.delete()
                    throw ArtworkCacheException("Escritura de imagen no íntegra")
                }
                return@withContext DownloadedArtwork(relative, sha, bounds.outWidth, bounds.outHeight, mime)
            }
        } catch (error: CancellationException) {
            temp.delete()
            throw error
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    private fun decodeBounds(file: File): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options
    }

    /** Bounds prove the header; one bounded sampled decode proves real pixel data. */
    private fun decodeProbe(file: File, bounds: BitmapFactory.Options) {
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw ArtworkCacheException("Imagen no decodificable")
        try {
            if (bitmap.width <= 0 || bitmap.height <= 0) {
                throw ArtworkCacheException("Imagen no decodificable")
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256Of(file: File): String = with(MessageDigest.getInstance("SHA-256")) {
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                update(buffer, 0, count)
            }
        }
        digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun execute(endpoint: okhttp3.HttpUrl): Response = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", MICHI_HTTP_USER_AGENT)
            .header("Accept", "image/jpeg,image/png,image/webp")
            .build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response)
                else response.close()
            }
        })
    }

    companion object {
        const val DIRECTORY = "artist-artwork"
        const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
        const val MAX_PIXELS = 20_000_000L
        private val ALLOWED_MIME = setOf("image/jpeg", "image/png", "image/webp")
    }
}

/** Verifies an existing private file before the UI is allowed to render it. */
suspend fun verifyCachedArtwork(context: Context, selection: ArtistArtworkSelection): ArtistArtworkSelection =
    withContext(Dispatchers.IO) {
        val path = selection.localImagePath ?: return@withContext selection.copy(status = ArtworkLoadStatus.CACHE_ERROR)
        val root = File(context.filesDir, ArtworkDownloader.DIRECTORY).canonicalFile
        val raw = File(path)
        // Inspect the link itself before canonicalization: canonicalFile resolves
        // symlinks, which would hide a link pointing at valid bytes.
        if (Files.isSymbolicLink(raw.toPath())) {
            return@withContext selection.copy(status = ArtworkLoadStatus.CACHE_ERROR)
        }
        val file = runCatching { raw.canonicalFile }.getOrNull()
            ?: return@withContext selection.copy(status = ArtworkLoadStatus.CACHE_ERROR)
        val expected = selection.assetId.removePrefix("sha256:").lowercase()
        val validPath = file.path.startsWith(root.path + File.separator) &&
            file.isFile && !Files.isSymbolicLink(file.toPath())
        if (!validPath || expected.length != 64 ||
            file.length() <= 0L || file.length() > ArtworkDownloader.MAX_IMAGE_BYTES) {
            return@withContext selection.copy(status = ArtworkLoadStatus.CACHE_ERROR)
        }
        val memoKey = "${file.path}|${file.length()}|${file.lastModified()}|$expected"
        val valid = verifiedFileCache.computeIfAbsent(memoKey) {
            sha256OfFile(file).equals(expected, ignoreCase = true) && probeDecodeFile(file)
        }
        selection.copy(status = if (valid) ArtworkLoadStatus.READY else ArtworkLoadStatus.CACHE_ERROR)
    }

private val verifiedFileCache = ConcurrentHashMap<String, Boolean>()

private fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun probeDecodeFile(file: File): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
    if (bounds.outWidth.toLong() * bounds.outHeight > ArtworkDownloader.MAX_PIXELS) return false
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
        ?: return false
    return try {
        bitmap.width > 0 && bitmap.height > 0
    } finally {
        bitmap.recycle()
    }
}
