package org.michimusic.mobile.artist

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Never opens remote addresses or scans the whole media payload. */
class LocalArtistTagProbe(private val context: Context) : ArtistTagProbe {
    override suspend fun inspect(localUris: List<String>): EmbeddedArtistReview = withContext(Dispatchers.IO) {
        val distinctUris = localUris.distinct().filter { runCatching { Uri.parse(it).scheme == "content" }.getOrDefault(false) }.take(24)
        val results = distinctUris.mapNotNull { uri ->
            try {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    val buffer = ByteArray(ArtistEmbeddedTagCodec.MAX_TAG_BYTES)
                    var total = 0
                    while (total < buffer.size) {
                        val n = stream.read(buffer, total, buffer.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    EmbeddedTrackEvidence(uri, ArtistEmbeddedTagCodec.parse(buffer.copyOf(total)))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: SecurityException) { null }
              catch (_: java.io.IOException) { null }
        }
        EmbeddedArtistReview(
            inspectedFiles = distinctUris.size,
            taggedFiles = results.count { it.tags.artistMbids.isNotEmpty() || it.tags.creditedNames.isNotEmpty() || it.tags.albumArtistMbids.isNotEmpty() },
            artistMbids = results.flatMap { it.tags.artistMbids }.toSet(),
            albumArtistMbids = results.flatMap { it.tags.albumArtistMbids }.toSet(),
            creditedNames = results.flatMap { it.tags.creditedNames }.distinct(),
            albumArtists = results.flatMap { it.tags.albumArtists }.distinct(),
            trackEvidence = results,
        )
    }
}
