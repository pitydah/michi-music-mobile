package org.michimusic.data.metadata

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.michimusic.core.metadata.MetadataReadAccessPolicy

/**
 * ME-06. Permission acquisition is NOT a mutation permit. The UI must launch
 * the system PendingIntent via Activity Result and return with the exact target
 * and transaction identity. No permission is requested on composition/scroll.
 */
internal class MetadataExactWritePermission(private val context: Context) {
    sealed interface Result {
        data class MediaStoreConsentRequired(val intent: PendingIntent) : Result
        data object ExistingExactGrant : Result
        data object NoGrant : Result
        data object InvalidTarget : Result
    }

    fun inspect(sourceId: String, uriString: String, userOpenedEditor: Boolean): Result {
        if (!userOpenedEditor) return Result.NoGrant
        val uri = try { Uri.parse(uriString) } catch (_: Exception) { return Result.InvalidTarget }
        if (uri.scheme != "content") return Result.InvalidTarget
        val kind = MetadataReadAccessPolicy.classify(sourceId, uri.scheme, uri.authority)
        if (kind == MetadataReadAccessPolicy.Kind.REJECT) return Result.InvalidTarget
        val holdsPermission = context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return when (kind) {
            MetadataReadAccessPolicy.Kind.MEDIASTORE -> {
                // Never request broad collection permissions or grant for another URI.
                // An app-owned MediaStore item is writable without an explicit
                // URI grant. Prove that authority with a non-truncating exact
                // descriptor probe before requesting system consent.
                if (holdsPermission || canOpenExactReadWrite(uri))
                    Result.ExistingExactGrant
                else try {
                    Result.MediaStoreConsentRequired(MediaStore.createWriteRequest(
                        context.contentResolver, listOf(uri)))
                } catch (_: Exception) { Result.NoGrant }
            }
            MetadataReadAccessPolicy.Kind.SAF_LOCAL -> {
                val document = try { DocumentsContract.isDocumentUri(context, uri) }
                    catch (_: Exception) { false }
                if (!document || !holdsPermission)
                    Result.NoGrant
                else if (supportsWrite(uri)) Result.ExistingExactGrant
                else Result.NoGrant
            }
            MetadataReadAccessPolicy.Kind.REJECT -> Result.InvalidTarget
        }
    }

    /** FLAG_SUPPORTS_WRITE is necessary, not sufficient to prove safe replacement. */
    private fun supportsWrite(uri: Uri): Boolean = try {
        context.contentResolver.query(uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use { cursor ->
            cursor.moveToFirst() && !cursor.isNull(0) &&
                (cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
        } == true
    } catch (_: Exception) { false }

    private fun canOpenExactReadWrite(uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
    }.getOrDefault(false)
}
