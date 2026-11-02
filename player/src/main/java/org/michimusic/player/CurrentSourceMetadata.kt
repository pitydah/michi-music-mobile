package org.michimusic.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

/** Facts extracted from the current FILE, distinct from decoded PCM and Android AudioTrack. */
data class SourceAudioFileFacts(
    val mimeType: String? = null,
    val bitrateBps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitsPerSample: Int = 0,
)

object CurrentSourceMetadata {
    private data class MetadataCacheEntry(val savedAtMs: Long, val facts: SourceAudioFileFacts)
    private val cache = object : LinkedHashMap<String, MetadataCacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MetadataCacheEntry>?): Boolean = size > 64
    }

    /** Call only from an IO dispatcher. Never scan the whole library or open a remote URL. */
    fun read(context: Context, track: Track): SourceAudioFileFacts? {
        val path = track.filepath.trim()
        if (path.isBlank() || track.source == TrackSource.STREAMING ||
            path.startsWith("http://", true) || path.startsWith("https://", true)) return null
        val key = "${track.id}|${track.filepath}|${track.size}|${track.dateAdded}"
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key]?.takeIf { now - it.savedAtMs in 0L..300_000L }?.facts }?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(path)
            when (uri.scheme?.lowercase()) {
                "content", "file" -> retriever.setDataSource(context, uri)
                null -> if (path.startsWith("/")) retriever.setDataSource(path) else return null
                else -> return null
            }
            fun intValue(key: Int, lo: Int, hi: Int): Int =
                retriever.extractMetadata(key)?.toIntOrNull()?.takeIf { it in lo..hi } ?: 0
            val facts = SourceAudioFileFacts(
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.takeIf { it.startsWith("audio/", ignoreCase = true) },
                bitrateBps = intValue(MediaMetadataRetriever.METADATA_KEY_BITRATE, 8_000, 50_000_000),
                sampleRateHz = intValue(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, 8_000, 768_000),
                bitsPerSample = intValue(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE, 8, 64),
            )
            synchronized(cache) { cache[key] = MetadataCacheEntry(now, facts) }
            facts
        } catch (_: Exception) {
            null // No fake bitrate if provider/URI/codec doesn't expose it
        } finally {
            runCatching { retriever.release() }
        }
    }
}
