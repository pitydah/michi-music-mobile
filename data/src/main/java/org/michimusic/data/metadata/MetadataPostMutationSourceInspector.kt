package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataPostSourceFieldPolicy
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataTarget

/**
 * ME-10T: second, independent read-only proof AFTER a future provider write.
 * The source must match the staged candidate byte-for-byte AND disclose every
 * selected field physically; unverifiable pipes/providers fail closed.
 * Uses a final whole-file read after field inspection to reject observable
 * mid-inspection replacement. Still not an atomic CAS guarantee on SAF.
 */
internal class MetadataPostMutationSourceInspector(private val context: Context) {
    enum class Reason { NO_USER_REQUEST, OWNER_CHANGED, BYTES_UNKNOWN,
        PARSE_UNAVAILABLE, FIELD_READBACK_FAILED, SOURCE_CHANGED_DURING_READ }
    sealed interface Result {
        data class Inspected(val changedFieldsVerified: Int) : Result {
            val mayPublishIndex: Boolean get() = false
        }
        data class Blocked(val reason: Reason) : Result
    }
    private val bytes = MetadataCurrentBytesReader(context)

    suspend fun inspect(sourceId: String, uri: String,
        target: MetadataTarget, manifest: MetadataCommitIntentManifest.Receipt,
        userRequested: Boolean, ownerCurrent: () -> Boolean,
        checkCancellation: () -> Unit = {}): Result = withContext(Dispatchers.IO) {
        if (!userRequested) return@withContext Result.Blocked(Reason.NO_USER_REQUEST)
        if (!ownerCurrent() || !MetadataCommitIntentManifest.valid(manifest) ||
            target.sourceId != sourceId || target.uri != uri ||
            MetadataPrivateSessionIdentity.digest(target) != manifest.ownerSha256)
            return@withContext Result.Blocked(Reason.OWNER_CHANGED)
        val size=manifest.candidateBytes
        val first=bytes.readCommittedCandidate(
            sourceId,uri,manifest.candidateBytes,manifest.candidateSha256,checkCancellation,
        )
        if (first !is MetadataCurrentBytesReader.PostCommitRead.Exact)
            return@withContext Result.Blocked(Reason.BYTES_UNKNOWN)
        val parsed=try {
            val descriptor=context.contentResolver.openFileDescriptor(Uri.parse(uri),"r")
                ?: return@withContext Result.Blocked(Reason.PARSE_UNAVAILABLE)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                MetadataColdFieldParser.observe(stream.channel)
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { return@withContext Result.Blocked(Reason.PARSE_UNAVAILABLE) }
        val fields=MetadataPostSourceFieldPolicy.compare(manifest,parsed)
        if (fields !is MetadataPostSourceFieldPolicy.Verdict.Compared)
            return@withContext Result.Blocked(Reason.FIELD_READBACK_FAILED)
        if (!ownerCurrent()) return@withContext Result.Blocked(Reason.OWNER_CHANGED)
        val second=bytes.readCommittedCandidate(
            sourceId,uri,manifest.candidateBytes,manifest.candidateSha256,checkCancellation,
        )
        if (second !is MetadataCurrentBytesReader.PostCommitRead.Exact ||
            second.actualSha256 != first.actualSha256 ||
            second.actualSize != first.actualSize)
            return@withContext Result.Blocked(Reason.SOURCE_CHANGED_DURING_READ)
        Result.Inspected(fields.verifiedChanges)
    }
}
