package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataReadAccessPolicy

/**
 * ME-06. Read back the SAME provider document after a hypothetical mutation.
 * Size is allowed to change, unlike ME-05 snapshot capture. Never writes source.
 */
internal class MetadataCurrentBytesReader(private val context: Context) {
    enum class Failure { INVALID_SOURCE, MISSING_STAMP, PERMISSION_DENIED, UNREADABLE,
        STAMP_CHANGED_DURING_READ, SIZE_MISMATCH, BUDGET_EXCEEDED }
    sealed interface Result {
        data class Read(val fingerprint: MetadataPhysicalOutcomePolicy.Observed) : Result
        data class Failed(val reason: Failure) : Result
    }

    sealed interface PostCommitRead {
        data class Exact(
            val actualSize: Long,
            val actualSha256: String,
            val mediaStoreAdvertisedSize: Long?,
            val mediaStoreAdvertisedModifiedAt: Long?,
            val metadataLagObserved: Boolean,
        ) : PostCommitRead
        data class Rejected(val reason: String) : PostCommitRead
    }
    private data class Stamp(val size: Long, val modified: Long)

    private fun stamp(uri: Uri, media: Boolean): Stamp? {
        val cols = if (media) arrayOf(MediaStore.Audio.Media.SIZE,MediaStore.Audio.Media.DATE_MODIFIED)
            else arrayOf(DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return context.contentResolver.query(uri,cols,null,null,null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1))
                Stamp(cursor.getLong(0),cursor.getLong(1)) else null
        }?.takeIf { it.size > 0 && it.modified > 0 }
    }

    fun read(sourceId: String, rawUri: String, maxBytes: Long,
        checkCancellation: () -> Unit = {}): Result {
        val uri = try { Uri.parse(rawUri) } catch (_: Exception) { return Result.Failed(Failure.INVALID_SOURCE) }
        val kind = MetadataReadAccessPolicy.classify(sourceId, uri.scheme, uri.authority)
        if (kind == MetadataReadAccessPolicy.Kind.REJECT || maxBytes !in 1..(1024L*1024*1024+128L*1024*1024))
            return Result.Failed(Failure.INVALID_SOURCE)
        val media = kind == MetadataReadAccessPolicy.Kind.MEDIASTORE
        if (!media) {
            val isDocument=try { DocumentsContract.isDocumentUri(context,uri) }
                catch (_: Exception) { false }
            if (!isDocument) return Result.Failed(Failure.INVALID_SOURCE)
        }
        try {
            val before = stamp(uri,media) ?: return Result.Failed(Failure.MISSING_STAMP)
            if (before.size > maxBytes) return Result.Failed(Failure.BUDGET_EXCEEDED)
            val hash = MessageDigest.getInstance("SHA-256")
            var read = 0L
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val chunk = ByteArray(64*1024)
                var empty = 0
                while(true) {
                    checkCancellation()
                    val n=stream.read(chunk)
                    if (n < 0) break
                    if (n==0) { if (++empty > 8) return Result.Failed(Failure.UNREADABLE); continue }
                    empty=0
                    read+=n
                    if (read>maxBytes) return Result.Failed(Failure.BUDGET_EXCEEDED)
                    hash.update(chunk,0,n)
                }
            } ?: return Result.Failed(Failure.UNREADABLE)
            val after = stamp(uri,media) ?: return Result.Failed(Failure.MISSING_STAMP)
            if (before != after) return Result.Failed(Failure.STAMP_CHANGED_DURING_READ)
            if (read!=after.size) return Result.Failed(Failure.SIZE_MISMATCH)
            val hex = hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
            return Result.Read(MetadataPhysicalOutcomePolicy.Observed(read,hex))
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: SecurityException) { return Result.Failed(Failure.PERMISSION_DENIED) }
          catch (_: Exception) { return Result.Failed(Failure.UNREADABLE) }
    }
    /**
     * ME-13 P0: MediaStore may lag after rwt. Exact physical bytes win; an
     * advertised-size mismatch is reported, never treated as a successful hash.
     */
    fun readCommittedCandidate(
        sourceId: String,
        rawUri: String,
        expectedCandidateBytes: Long,
        expectedCandidateSha256: String,
        checkCancellation: () -> Unit = {},
    ): PostCommitRead {
        val uri = try { Uri.parse(rawUri) } catch (_: Exception) {
            return PostCommitRead.Rejected("INVALID_URI")
        }
        if (uri.scheme != "content" || uri.authority != "media" ||
            sourceId != "lib_device_local" || expectedCandidateBytes !in 1..(1024L*1024*1024))
            return PostCommitRead.Rejected("INVALID_SOURCE")
        val before = stamp(uri, true) ?: return PostCommitRead.Rejected("MISSING_STAMP")
        val hash = MessageDigest.getInstance("SHA-256")
        var actual = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val chunk = ByteArray(64 * 1024)
                var empty = 0
                while (true) {
                    checkCancellation()
                    val n = stream.read(chunk)
                    if (n < 0) break
                    if (n == 0) {
                        if (++empty > 8) return PostCommitRead.Rejected("UNREADABLE")
                        continue
                    }
                    empty = 0
                    actual += n
                    if (actual > expectedCandidateBytes)
                        return PostCommitRead.Rejected("ACTUAL_SIZE_MISMATCH")
                    hash.update(chunk, 0, n)
                }
            } ?: return PostCommitRead.Rejected("UNREADABLE")
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: SecurityException) { return PostCommitRead.Rejected("PERMISSION_DENIED") }
          catch (_: Exception) { return PostCommitRead.Rejected("UNREADABLE") }
        val after = stamp(uri, true) ?: return PostCommitRead.Rejected("MISSING_STAMP")
        if (before != after) return PostCommitRead.Rejected("STAMP_CHANGED_DURING_READ")
        val sha = hash.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        if (actual != expectedCandidateBytes)
            return PostCommitRead.Rejected("ACTUAL_SIZE_MISMATCH")
        if (sha != expectedCandidateSha256)
            return PostCommitRead.Rejected("SHA_MISMATCH")
        return PostCommitRead.Exact(actual, sha, after.size, after.modified,
            metadataLagObserved = after.size != actual)
    }

}
