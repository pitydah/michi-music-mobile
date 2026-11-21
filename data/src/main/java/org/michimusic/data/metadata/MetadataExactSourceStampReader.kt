package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataReadAccessPolicy

/**
 * ME-11I: observe an exact provider source's post-write stamp on demand.
 * Unknown, zero, multiple, mismatched or inaccessible provider stamps are
 * NEVER substituted with wall-clock time or the old cached timestamp.
 * This never opens a write descriptor, scans a library or grants commit rights.
 */
internal class MetadataExactSourceStampReader(private val context: Context) {
    data class Stamp(val sizeBytes: Long, val modifiedAtProviderUnits: Long)

    suspend fun read(sourceId: String, uriString: String,
                     expectedSize: Long): Stamp? = withContext(Dispatchers.IO) {
        if (expectedSize !in 12..(1024L * 1024L * 1024L)) return@withContext null
        val uri = try { Uri.parse(uriString) } catch (_: Exception) { return@withContext null }
        val kind = MetadataReadAccessPolicy.classify(sourceId, uri.scheme, uri.authority)
        val (sizeColumn, modifiedColumn) = when (kind) {
            MetadataReadAccessPolicy.Kind.MEDIASTORE ->
                MediaStore.MediaColumns.SIZE to MediaStore.MediaColumns.DATE_MODIFIED
            MetadataReadAccessPolicy.Kind.SAF_LOCAL -> {
                if (!DocumentsContract.isDocumentUri(context, uri)) return@withContext null
                DocumentsContract.Document.COLUMN_SIZE to DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }
            MetadataReadAccessPolicy.Kind.REJECT -> return@withContext null
        }
        try {
            context.contentResolver.query(uri, arrayOf(sizeColumn, modifiedColumn),
                null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1))
                    return@withContext null
                val size = cursor.getLong(0)
                val modifiedAt = cursor.getLong(1)
                if (cursor.moveToNext() || size != expectedSize || modifiedAt <= 0L)
                    return@withContext null
                Stamp(size, modifiedAt)
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { null }
    }
}
