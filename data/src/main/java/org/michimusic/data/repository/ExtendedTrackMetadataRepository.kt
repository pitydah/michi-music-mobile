package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.TrackDao

/** Structured, locally cached details, never part of the Michi Link DTO. */
data class ExtendedTrackMetadata(
    val sourceId: String,
    val trackId: String,
    val composer: String,
    val trackNumber: Int?,
    val trackTotal: Int?,
    val discNumber: Int?,
    val discTotal: Int?,
    val date: String,
    /** Independent original tag fields. A literal semicolon is preserved. */
    val genres: List<String>,
)

/**
 * Cold owner-aware Room lookups. The user-opened verified path rereads bounded tags
 * once, and cannot promote stale sidecar values based on provider size/mtime alone.
 * Compatibility load/loadBatch remain cache-only; not UI entrypoints.
 */
class ExtendedTrackMetadataRepository(
    private val trackDao: TrackDao,
    private val context: Context? = null,
) {
    /**
     * This is the ONLY UI-facing cold details method. Location + provider stamp
     * are checked both before and after the Room lookup, refusing changed documents,
     * unavailable permissions, mtime=0, or mismatching owner/path/size/version.
     * Same-byte-count + same-mtime replacement remains inherently unverifiable.
     */
    suspend fun loadVerified(
        sourceId: String, trackId: String, filepath: String, expectedSize: Long,
    ): ExtendedTrackMetadata? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        if (!filepath.startsWith("content://") || trackId.isBlank() || expectedSize <= 0)
            return@withContext null
        val uri = Uri.parse(filepath)
        val sizeCol = if (sourceId.isEmpty()) MediaStore.Audio.Media.SIZE
            else DocumentsContract.Document.COLUMN_SIZE
        val modifiedCol = if (sourceId.isEmpty()) MediaStore.Audio.Media.DATE_MODIFIED
            else DocumentsContract.Document.COLUMN_LAST_MODIFIED
        fun stamp(): RecoveryDocumentStamp? = try {
            ctx.contentResolver.query(uri, arrayOf(sizeCol, modifiedCol), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) null
                else RecoveryDocumentStamp(cursor.getLong(0), cursor.getLong(1))
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
        val before = stamp()
        if (before == null || before.size != expectedSize || before.modifiedAt <= 0)
            return@withContext null
        val evidence = trackDao.getCurrentExtendedTagEvidenceForPath(
            sourceId, trackId, filepath, expectedSize)
        if (evidence == null) return@withContext null
        // A provider may recycle URI, size and mtime. Compare the actual tag fields
        // only on this explicit details tap, never in card/list recompositions.
        // A malformed/unsupported tag fails closed rather than trusting old Room data.
        val currentTags = EmbeddedTagReadPolicy.fieldsOrNull(
            UriEmbeddedTags.readDetailed(ctx, uri)) ?: return@withContext null
        val after = stamp()
        if (!ColdMetadataStampPolicy.matches(
                sourceId, trackId, filepath, expectedSize, before, after, evidence) ||
            !ColdMetadataTagEquivalence.matches(evidence, currentTags))
            return@withContext null
        evidence.toExtendedMetadata()
    }

    suspend fun load(sourceId: String, trackId: String): ExtendedTrackMetadata? =
        withContext(Dispatchers.IO) {
            if (trackId.isBlank()) null
            else trackDao.getCurrentExtendedTagEvidence(sourceId, trackId)?.toExtendedMetadata()
        }

    /** Caller must chunk requests to preserve a strict bounded bind-variable budget. */
    suspend fun loadBatch(
        sourceId: String, trackIds: List<String>,
    ): List<ExtendedTrackMetadata> = withContext(Dispatchers.IO) {
        val ids = trackIds.distinct().also { require(it.size <= 128) }
        if (ids.isEmpty()) emptyList()
        else trackDao.getCurrentExtendedTagEvidenceBatch(sourceId, ids)
            .map(EmbeddedTagEvidence::toExtendedMetadata)
    }
}

internal fun EmbeddedTagEvidence.toExtendedMetadata(): ExtendedTrackMetadata =
    ExtendedTrackMetadata(
        sourceId = sourceId,
        trackId = trackId,
        composer = composer,
        trackNumber = trackNumber.takeIf { it > 0 },
        trackTotal = trackTotal.takeIf { it > 0 },
        discNumber = discNumber.takeIf { it > 0 },
        discTotal = discTotal.takeIf { it > 0 },
        date = date,
        genres = TrustedGenreEncoding.decode(genre, genreValues),
    )
