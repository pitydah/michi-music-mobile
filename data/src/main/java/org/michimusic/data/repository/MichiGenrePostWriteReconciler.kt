package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway

/**
 * MI-24: an indexed genre may be published only AFTER file bytes and tags were verified
 * by MichiGenreTagWriteGateway. The Room update is owner+track+URI+old-size guarded;
 * a database failure never changes the outcome of the already-committed audio write.
 * This is an explicitly invoked one-track operation, not a new library scanner.
 */
class MichiGenrePostWriteReconciler(
    private val context: Context,
    private val dao: TrackDao,
) {
    sealed interface Outcome {
        data object Indexed : Outcome
        data class RefreshRequired(val reason: String) : Outcome
    }

    private data class Stamp(val size: Long, val modifiedAt: Long)
    private fun stamp(uri: Uri): Stamp? = try {
        val modifiedKey = if (uri.authority == MediaStore.AUTHORITY)
            MediaStore.Audio.Media.DATE_MODIFIED else DocumentsContract.Document.COLUMN_LAST_MODIFIED
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, modifiedKey),
            null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) null
            else Stamp(cursor.getLong(0), cursor.getLong(1))
                .takeIf { it.size > 0 && it.modifiedAt > 0 }
        }
    } catch (_: Exception) { null }

    suspend fun reconcile(
        ticket: MichiGenreTagWriteGateway.Ticket,
        verified: MichiGenreTagWriteGateway.Result.SavedVerified,
        stillOwns: () -> Boolean,
    ): Outcome = withContext(Dispatchers.IO) {
        if (!stillOwns() || ticket.trackId.isBlank() ||
            ticket.uri.isBlank() || verified.format == "UNCHANGED")
            return@withContext Outcome.RefreshRequired("NO_INDEX_CHANGE_AUTHORIZED")
        val uri = Uri.parse(ticket.uri)
        if (uri.scheme != "content")
            return@withContext Outcome.RefreshRequired("NOT_LOCAL_CONTENT")
        val old = try { dao.getTrackBySource(ticket.sourceId, ticket.trackId) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
            ?: return@withContext Outcome.RefreshRequired("NO_CACHED_OWNER_ROW")
        if (old.filepath != ticket.uri || old.size != ticket.expectedSize ||
            old.id != ticket.trackId || old.sourceId != ticket.sourceId)
            return@withContext Outcome.RefreshRequired("INDEX_OWNER_OR_STAMP_CHANGED")
        val before = stamp(uri) ?: return@withContext Outcome.RefreshRequired("NO_PROVIDER_STAMP")
        val fields = when (val read = UriEmbeddedTags.readDetailed(context, uri)) {
            is EmbeddedTagReadResult.Parsed -> read.fields
            else -> return@withContext Outcome.RefreshRequired("POSTWRITE_TAGS_NOT_VERIFIABLE")
        }
        val after = stamp(uri) ?: return@withContext Outcome.RefreshRequired("NO_PROVIDER_STAMP")
        if (before != after || !stillOwns())
            return@withContext Outcome.RefreshRequired("SOURCE_CHANGED_DURING_INDEX_READ")
        val genres = TagGenreBounds.safeValues(fields)
            ?: return@withContext Outcome.RefreshRequired("INVALID_POSTWRITE_GENRE_VALUES")
        if (genres != verified.values)
            return@withContext Outcome.RefreshRequired("GENRE_REREAD_DIFFERS")
        val evidence = TagEvidencePolicy.record(ticket.sourceId, ticket.trackId,
            after.size, after.modifiedAt, fields)
        if (evidence.parserVersion != EmbeddedTagEvidence.CURRENT_PARSER_VERSION ||
            evidence.genreValues != verified.values.joinToString("\u0000"))
            return@withContext Outcome.RefreshRequired("EVIDENCE_MISMATCH")
        val written = try {
            if (!stillOwns()) false else dao.replaceGenreAfterVerifiedWrite(
                ticket.sourceId, ticket.trackId, ticket.uri,
                old.size, old.genre, after.size, evidence,
            )
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
        if (written && stillOwns()) Outcome.Indexed
        else Outcome.RefreshRequired("GUARDED_ROOM_COMMIT_REJECTED")
    }
}
