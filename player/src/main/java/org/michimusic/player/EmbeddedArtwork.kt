package org.michimusic.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Track
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Extracts embedded artwork from local/SAF/synced files for the Media3 session.
 *
 * The bitmap is decoded off the main thread and re-encoded as a small JPEG so
 * the metadata can cross the Binder boundary safely. Results are cached by a
 * file-identity key, so replacing a file or changing its metadata invalidates
 * the previous cover. Remote URLs are never fetched from the notification.
 */
internal object EmbeddedArtwork {
    private const val MAX_DIMENSION = 512
    private const val MAX_BYTES = 256 * 1024
    private const val CACHE_BYTES = 8 * 1024 * 1024
    private const val MISS_TTL_MS = 60_000L

    private val cache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val missing = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 256
    }

    suspend fun load(context: Context, track: Track): ByteArray? = withContext(Dispatchers.IO) {
        val key = cacheKey(context, track)
        synchronized(cache) { cache.get(key) }?.let { return@withContext it }
        val retryAt = synchronized(missing) { missing[key] }
        if (retryAt != null && System.currentTimeMillis() < retryAt) return@withContext null
        if (retryAt != null) synchronized(missing) { missing.remove(key) }

        val bytes = extract(context, track)
        if (bytes != null) {
            synchronized(cache) { cache.put(key, bytes) }
            synchronized(missing) { missing.remove(key) }
        } else {
            synchronized(missing) { missing[key] = System.currentTimeMillis() + MISS_TTL_MS }
        }
        bytes
    }

    private fun cacheKey(context: Context, track: Track): String {
        val path = track.filepath
        val identity = if (path.startsWith("/")) {
            val file = File(path)
            "${file.length()}|${file.lastModified()}"
        } else {
            val length = runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use { it.length }
            }.getOrNull() ?: track.size
            "$length|${track.coverId}|${track.title}|${track.artist}|${track.album}"
        }
        return "$path|$identity"
    }

    private fun extract(context: Context, track: Track): ByteArray? {
        val path = track.filepath
        if (path.isBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return null

        val retriever = MediaMetadataRetriever()
        return try {
            when {
                path.startsWith("content://") -> retriever.setDataSource(context, Uri.parse(path))
                path.startsWith("file://") -> retriever.setDataSource(context, Uri.parse(path))
                path.startsWith("/") -> retriever.setDataSource(path)
                else -> return null
            }
            val raw = retriever.embeddedPicture ?: return null
            if (raw.isEmpty()) return null
            compress(raw)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun compress(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (maxDimension / sampleSize > MAX_DIMENSION) sampleSize *= 2

        val bitmap = BitmapFactory.decodeByteArray(
            raw,
            0,
            raw.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        return try {
            val scaled = scaleDown(bitmap, MAX_DIMENSION)
            try {
                for (quality in intArrayOf(86, 76, 64)) {
                    val output = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    val bytes = output.toByteArray()
                    if (bytes.isNotEmpty() && bytes.size <= MAX_BYTES) return bytes
                }
                null
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
