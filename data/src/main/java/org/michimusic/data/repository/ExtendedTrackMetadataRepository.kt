package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataSnapshot
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
 * K47-NORM-02: why a cold tag read could not prove the physical values. The
 * reason is a fact about the read, never a guess: an unavailable read must not
 * be rendered as "the file has no genre".
 */
enum class ExtendedTagUnavailableReason {
    NOT_CONTENT_URI,
    INVALID_TRACK_ID,
    INVALID_SIZE,
    MISSING_STAMP,
    SIZE_MISMATCH,
    MODIFIED_AT_ZERO,
    NO_CURRENT_EVIDENCE,
    TAG_READER_UNSUPPORTED,
    TAG_MISMATCH,
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
}

sealed interface ExtendedTagEvidenceResult {
    data class Verified(
        val metadata: ExtendedTrackMetadata,
        /** Only from the SAME cold, owner-checked embedded read; never a write grant. */
        val editorSnapshot: MetadataSnapshot? = null,
    ) : ExtendedTagEvidenceResult
    data class Unavailable(val reason: ExtendedTagUnavailableReason) : ExtendedTagEvidenceResult
}

sealed interface MetadataEditorEvidenceResult {
    data class Verified(val snapshot: MetadataSnapshot) : MetadataEditorEvidenceResult
    data class Unavailable(val reason: ExtendedTagUnavailableReason) : MetadataEditorEvidenceResult
}

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
    ): ExtendedTrackMetadata? =
        (loadVerifiedResult(sourceId, trackId, filepath, expectedSize)
            as? ExtendedTagEvidenceResult.Verified)?.metadata

    /**
     * ME-01B: editor entrypoint. Reuses EXACTLY the existing cold verified path;
     * does not trigger a second parser pass or fabricate titles from MediaStore.
     */
    suspend fun loadVerifiedEditorSnapshotResult(
        sourceId: String, trackId: String, filepath: String, expectedSize: Long,
    ): MetadataEditorEvidenceResult =
        when (val result = loadVerifiedResult(sourceId, trackId, filepath, expectedSize)) {
            is ExtendedTagEvidenceResult.Unavailable ->
                MetadataEditorEvidenceResult.Unavailable(result.reason)
            is ExtendedTagEvidenceResult.Verified -> result.editorSnapshot?.let {
                MetadataEditorEvidenceResult.Verified(it)
            } ?: MetadataEditorEvidenceResult.Unavailable(ExtendedTagUnavailableReason.TAG_READER_UNSUPPORTED)
        }

    /**
     * Same proof as [loadVerified] (kept for compatibility) but with a typed
     * reason, so the editor can explain what actually happened instead of
     * implying the file has no genre.
     */
    suspend fun loadVerifiedResult(
        sourceId: String, trackId: String, filepath: String, expectedSize: Long,
    ): ExtendedTagEvidenceResult = withContext(Dispatchers.IO) {
        if (!filepath.startsWith("content://"))
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.NOT_CONTENT_URI)
        if (trackId.isBlank())
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.INVALID_TRACK_ID)
        if (expectedSize <= 0)
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.INVALID_SIZE)
        val ctx = context
            ?: return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE)
        val uri = Uri.parse(filepath)
        val sizeCol = if (sourceId.isEmpty()) MediaStore.Audio.Media.SIZE
            else DocumentsContract.Document.COLUMN_SIZE
        val modifiedCol = if (sourceId.isEmpty()) MediaStore.Audio.Media.DATE_MODIFIED
            else DocumentsContract.Document.COLUMN_LAST_MODIFIED
        var denied = false
        var queryFailed = false
        fun stamp(): RecoveryDocumentStamp? = try {
            ctx.contentResolver.query(uri, arrayOf(sizeCol, modifiedCol), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) null
                else RecoveryDocumentStamp(cursor.getLong(0), cursor.getLong(1))
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: SecurityException) { denied = true; null }
        catch (_: Exception) { queryFailed = true; null }
        val before = stamp()
        if (before == null) {
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                when {
                    denied -> ExtendedTagUnavailableReason.PERMISSION_DENIED
                    queryFailed -> ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE
                    else -> ExtendedTagUnavailableReason.MISSING_STAMP
                })
        }
        if (before.size != expectedSize)
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.SIZE_MISMATCH)
        if (before.modifiedAt <= 0)
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.MODIFIED_AT_ZERO)
        val evidence = trackDao.getCurrentExtendedTagEvidenceForPath(
            sourceId, trackId, filepath, expectedSize)
            ?: return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.NO_CURRENT_EVIDENCE)
        // A provider may recycle URI, size and mtime. Compare the actual tag fields
        // only on this explicit details tap, never in card/list recompositions.
        // A malformed/unsupported tag fails closed rather than trusting old Room data.
        val coldRead = UriEmbeddedTags.readEditorDetailed(ctx, uri)
        val currentTags = EmbeddedTagReadPolicy.fieldsOrNull(
            coldRead.legacy)
            ?: return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.TAG_READER_UNSUPPORTED)
        val after = stamp()
        if (after == null) {
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                when {
                    denied -> ExtendedTagUnavailableReason.PERMISSION_DENIED
                    queryFailed -> ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE
                    else -> ExtendedTagUnavailableReason.MISSING_STAMP
                })
        }
        if (!ColdMetadataStampPolicy.matches(
                sourceId, trackId, filepath, expectedSize, before, after, evidence)) {
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.TAG_MISMATCH)
        }
        if (!ColdMetadataTagEquivalence.matches(evidence, currentTags))
            return@withContext ExtendedTagEvidenceResult.Unavailable(
                ExtendedTagUnavailableReason.TAG_MISMATCH)
        ExtendedTagEvidenceResult.Verified(
            metadata = evidence.toExtendedMetadata(),
            editorSnapshot = MetadataEditorEvidenceMapper.fromColdVerifiedRead(
                sourceId, trackId, filepath, after, currentTags, coldRead.report),
        )
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
