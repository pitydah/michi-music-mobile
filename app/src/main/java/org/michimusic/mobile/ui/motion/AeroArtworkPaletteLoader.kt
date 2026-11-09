package org.michimusic.mobile.ui.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import android.util.LruCache
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cache dark colors, not bitmaps; bounded to 64 entries in process memory. */
internal object AeroArtworkPaletteLoader {
    private val cache = LruCache<String, AeroPaletteCacheEntry>(64)

    suspend fun resolve(context: Context, coverId: String?, mediaUri: String?): AeroDynamicPalette =
        withContext(Dispatchers.IO) {
            val cover = coverId?.trim().orEmpty()
            val media = mediaUri?.trim().orEmpty()
            if (!aeroPaletteHasLocalSource(cover, media)) return@withContext AeroDefaultDynamicPalette
            val key = "$cover\u0000$media"
            val cached = synchronized(cache) { cache.get(key) }
            if (aeroPaletteCacheUsable(cached, SystemClock.elapsedRealtime())) {
                return@withContext requireNotNull(cached).palette
            }
            val bitmap = try {
                readLocalArtwork(context.applicationContext, cover, media)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Permission revoked or malformed cover; preserve Aero fallback.
                null
            }
            // A decoded bitmap can still fail while pixels are being sampled
            // (revoked provider, recycled image or malformed decoder result).
            // Keep the UI responsive, and let the short negative-cache TTL retry.
            val (result, decodedArtwork) = if (bitmap == null) {
                AeroDefaultDynamicPalette to false
            } else try {
                val columns = 20
                val rows = 20
                val pixels = IntArray(columns * rows) { index ->
                    val x = ((index % columns) * bitmap.width / columns).coerceIn(0, bitmap.width - 1)
                    val y = ((index / columns) * bitmap.height / rows).coerceIn(0, bitmap.height - 1)
                    bitmap.getPixel(x, y)
                }
                aeroDynamicPaletteFromPixels(pixels) to true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                AeroDefaultDynamicPalette to false
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            // Suspend cancellation is authoritative: a skip must not populate
            // the cache with a result returned after the old request was cancelled.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            synchronized(cache) {
                cache.put(key, aeroPaletteCacheEntry(
                    palette = result,
                    decodedArtwork = decodedArtwork,
                    elapsedMs = SystemClock.elapsedRealtime(),
                ))
            }
            result
        }

    private fun readLocalArtwork(context: Context, coverId: String, mediaUri: String): Bitmap? {
        // The same numeric MediaStore cover IDs that AlbumArtView already supports.
        val coverUri = when {
            coverId.toLongOrNull()?.let { it > 0 } == true ->
                Uri.parse("content://media/external/audio/albumart/$coverId")
            else -> Uri.parse(coverId).takeIf { it.scheme == "content" || it.scheme == "file" }
        }
        if (coverUri != null) {
            try {
                fun open() = context.contentResolver.openInputStream(coverUri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var sample = 1
                    while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) sample *= 2
                    val decoded = open()?.use { stream ->
                        BitmapFactory.decodeStream(stream, null,
                            BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    if (decoded != null) return decoded
                }
            } catch (_: Exception) {
                // Invalid/revoked artwork: try the matching local audio file instead.
            }
        }
        val uri = if (mediaUri.startsWith("/") && !mediaUri.startsWith("//")) {
            Uri.fromFile(File(mediaUri))
        } else Uri.parse(mediaUri)
        if (uri.scheme != "content" && uri.scheme != "file") return null
        return try {
            val retriever = MediaMetadataRetriever()
            val picture = try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } finally {
                retriever.release()
            } ?: return null
            if (picture.size > 8 * 1024 * 1024) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) sample *= 2
            BitmapFactory.decodeByteArray(picture, 0, picture.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }
}
