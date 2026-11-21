package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import org.michimusic.core.metadata.MetadataSafRenameReadbackEvidence
import org.michimusic.core.metadata.MetadataSafRenameRecoveryPolicy

/**
 * ME-11P. Explicit user-triggered independent SAF recovery inspection.
 * This reads a maximum of two target candidates; other child rows are metadata
 * only. It does not scan the library, rename, write a URI, or update Room.
 *
 * A provider-specific certificate of complete enumeration is REQUIRED; a cursor
 * without EXTRA_LOADING does not by itself prove a complete cloud folder.
 */
internal class MetadataSafRenameAndroidEvidenceReader(private val context: Context) {
    data class Request(
        val sourceId: String,
        val trackId: String,
        /** The exact persisted ACTION_OPEN_DOCUMENT_TREE grant, not a guessed URI. */
        val persistedTreeUri: String,
        val userInitiated: Boolean,
        val ownerStillCurrent: Boolean,
        val playbackClosed: Boolean,
        /** Outside evidence from provider instrumented trials, never a UI toggle. */
        val providerListingCertified: Boolean = false,
    )
    enum class Reason {
        NOT_USER_INITIATED, WRONG_OWNER, PLAYBACK_ACTIVE, UNCERTIFIED_PROVIDER,
        NO_PERSISTED_TREE_GRANT, INVALID_PARENT, INVALID_PROVIDER_DATA, INCOMPLETE_PARENT_LIST,
        READBACK_FAILED, POLICY_REJECTED,
    }
    sealed interface Result {
        data class Blocked(val reason: Reason) : Result
        data class Observed(val evidence: MetadataSafRenameRecoveryPolicy.Observation) : Result
    }

    private val resolver get() = context.contentResolver
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    private val parentProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    private val maxBytes = 1024L * 1024L * 1024L
    private fun fold(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    suspend fun observe(
        intent: MetadataSafRenameRecoveryPolicy.Intent,
        request: Request,
        durablyReturnedUri: String?,
    ): Result = withContext(Dispatchers.IO) {
        fun blocked(reason: Reason): Result = Result.Blocked(reason)
        if (!request.userInitiated) return@withContext blocked(Reason.NOT_USER_INITIATED)
        if (!request.ownerStillCurrent || request.sourceId != intent.sourceId ||
            request.trackId != intent.trackId) return@withContext blocked(Reason.WRONG_OWNER)
        if (!request.playbackClosed) return@withContext blocked(Reason.PLAYBACK_ACTIVE)
        if (!request.providerListingCertified)
            return@withContext blocked(Reason.UNCERTIFIED_PROVIDER)
        try {
            val parent = Uri.parse(intent.parentUri)
            val tree = Uri.parse(request.persistedTreeUri)
            val original = Uri.parse(intent.beforeUri)
            if (parent.scheme != "content" || tree.scheme != "content" ||
                !DocumentsContract.isTreeUri(parent) || !DocumentsContract.isTreeUri(tree) ||
                !DocumentsContract.isDocumentUri(context,parent) ||
                parent.authority != tree.authority || original.authority != tree.authority ||
                DocumentsContract.getTreeDocumentId(parent) != DocumentsContract.getTreeDocumentId(tree))
                return@withContext blocked(Reason.INVALID_PARENT)
            // A persisted read grant is a necessary property, not proof of provider
            // correctness, write capability or ownership of a track in the library.
            val grantValid = resolver.persistedUriPermissions.any { grant ->
                grant.uri == tree && grant.isReadPermission
            }
            if (!grantValid) return@withContext blocked(Reason.NO_PERSISTED_TREE_GRANT)
            val parentId = DocumentsContract.getDocumentId(parent)
            val actualParent = resolver.query(parent,parentProjection,null,null,null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(parentProjection[0])
                val typeCol = cursor.getColumnIndex(parentProjection[1])
                if (idCol < 0 || typeCol < 0 || !cursor.moveToFirst()) false
                else {
                    val matches = !cursor.isNull(idCol) && !cursor.isNull(typeCol) &&
                        cursor.getString(idCol) == parentId &&
                        cursor.getString(typeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                    matches && !cursor.moveToNext()
                }
            } ?: false
            if (!actualParent) return@withContext blocked(Reason.INVALID_PARENT)
            val first = scan(parent, parentId, intent, durablyReturnedUri)
                ?: return@withContext blocked(Reason.INVALID_PROVIDER_DATA)
            if (!first.completelyEnumerated)
                return@withContext blocked(Reason.INCOMPLETE_PARENT_LIST)
            val matching = first.rows.filter {
                fold(it.name) == fold(intent.beforeName) ||
                    fold(it.name) == fold(intent.requestedName)
            }
            // Duplicate/ambiguous names may not be resolved by opening arbitrary
            // siblings. Preserve the uncertain state for manual recovery.
            if (matching.size > 2 ||
                matching.map { fold(it.name) }.distinct().size != matching.size)
                return@withContext blocked(Reason.INVALID_PROVIDER_DATA)
            val probes = matching.map { row ->
                fingerprint(row) ?: return@withContext blocked(Reason.READBACK_FAILED)
            }
            val second = scan(parent,parentId,intent,durablyReturnedUri)
                ?: return@withContext blocked(Reason.INVALID_PROVIDER_DATA)
            val assembled = MetadataSafRenameReadbackEvidence.assemble(
                intent=intent,sourceId=request.sourceId,trackId=request.trackId,
                first=first,second=second,probes=probes,
                providerListingCertified=request.providerListingCertified,
                parentDocumentVerified=actualParent,
                persistedTreeReadGrant=grantValid,
                playbackClosed=request.playbackClosed,
            )
            when (assembled) {
                is MetadataSafRenameReadbackEvidence.Result.Ready ->
                    Result.Observed(assembled.observation)
                is MetadataSafRenameReadbackEvidence.Result.Rejected ->
                    blocked(Reason.POLICY_REJECTED)
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: SecurityException) { blocked(Reason.NO_PERSISTED_TREE_GRANT) }
          catch (_: Exception) { blocked(Reason.READBACK_FAILED) }
    }

    private fun scan(
        parent: Uri, parentId: String,
        intent: MetadataSafRenameRecoveryPolicy.Intent,
        durablyReturnedUri: String?,
    ): MetadataSafRenameReadbackEvidence.ParentScan? {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent,parentId)
        return resolver.query(childUri,projection,null,null,null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(projection[0])
            val nameCol = cursor.getColumnIndex(projection[1])
            val sizeCol = cursor.getColumnIndex(projection[2])
            if (idCol < 0 || nameCol < 0 || sizeCol < 0) return@use null
            val loading = cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING,false) ?: false
            val output = ArrayList<MetadataSafRenameReadbackEvidence.Row>()
            while (cursor.moveToNext()) {
                if (output.size >= 4096 || cursor.isNull(idCol) || cursor.isNull(nameCol))
                    return@use null
                val id = cursor.getString(idCol) ?: return@use null
                val name = cursor.getString(nameCol) ?: return@use null
                val size = if (cursor.isNull(sizeCol)) null else cursor.getLong(sizeCol)
                val alias = when {
                    id == intent.beforeDocumentId -> intent.beforeUri
                    durablyReturnedUri != null &&
                        DocumentsContract.getDocumentId(Uri.parse(durablyReturnedUri)) == id ->
                        durablyReturnedUri
                    else -> DocumentsContract.buildDocumentUriUsingTree(parent,id).toString()
                }
                output += MetadataSafRenameReadbackEvidence.Row(id,alias,name,size)
            }
            MetadataSafRenameReadbackEvidence.ParentScan(
                parent.toString(),!loading,output)
        }
    }

    private fun fingerprint(row: MetadataSafRenameReadbackEvidence.Row):
        MetadataSafRenameReadbackEvidence.ColdProbe? {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64*1024)
        var size = 0L
        var zeroReads = 0
        val uri = Uri.parse(row.uri)
        resolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    if (++zeroReads > 32) return null // refuse a non-progressing pipe
                    continue
                }
                zeroReads = 0
                size += read
                if (size > maxBytes) return null
                md.update(buffer,0,read)
            }
        } ?: return null
        if (size <= 0L) return null
        return MetadataSafRenameReadbackEvidence.ColdProbe(
            uri=row.uri,bytesRead=size,
            sha256=md.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
            verifiedEndOfStream=true)
    }
}
