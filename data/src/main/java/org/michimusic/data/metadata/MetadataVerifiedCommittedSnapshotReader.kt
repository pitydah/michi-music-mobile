package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataPostSourceFieldPolicy
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

/**
 * ME-13 85-A: builds the UI snapshot from the COMMITTED source bytes.
 *
 * The editor draft and the private stage are not proofs of what is installed.
 * This reader re-validates the exact transaction manifest, reads the committed
 * candidate bytes twice, parses the provider source between those reads, and
 * only then projects a [MetadataSnapshot]. Every present/absent observation is
 * cross-checked against `known`, so a snapshot can never carry a new title with
 * old field observations.
 */
internal class MetadataVerifiedCommittedSnapshotReader(context: Context) {
    sealed interface Result {
        data class Verified(val snapshot: MetadataSnapshot) : Result
        data class Refused(val code: String) : Result
    }

    private val resolver = context.contentResolver
    private val current = MetadataCurrentBytesReader(context)

    suspend fun read(
        entry: MetadataRecoveryJournalV1.Entry,
        target: MetadataTarget,
        intent: MetadataCommitIntentManifest.Receipt,
        checkCancellation: () -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        if (!MetadataCommitIntentManifest.valid(intent) ||
            intent.transactionId != entry.id ||
            target.sourceId != entry.sourceId ||
            target.trackId != entry.trackId ||
            target.uri != entry.uri ||
            intent.candidateSha256 != entry.stageSha256 ||
            intent.candidateBytes != entry.stageBytes ||
            intent.originalSha256 != entry.originalSha256 ||
            intent.originalBytes != entry.expectedBytes ||
            intent.ownerSha256 != MetadataPrivateSessionIdentity.digest(target))
            return@withContext Result.Refused("SNAPSHOT_WRONG_TRANSACTION")

        val first = current.readCommittedCandidate(
            entry.sourceId, entry.uri, intent.candidateBytes, intent.candidateSha256,
            checkCancellation,
        )
        if (first !is MetadataCurrentBytesReader.PostCommitRead.Exact)
            return@withContext Result.Refused(
                refusalFor((first as MetadataCurrentBytesReader.PostCommitRead.Rejected).reason))

        val report = try {
            val descriptor = resolver.openFileDescriptor(Uri.parse(entry.uri), "r")
                ?: return@withContext Result.Refused("SOURCE_COLD_READ_FAILED")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                MetadataColdFieldParser.observe(stream.channel)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return@withContext Result.Refused("SOURCE_TAG_PARSE_FAILED")
        }

        val second = current.readCommittedCandidate(
            entry.sourceId, entry.uri, intent.candidateBytes, intent.candidateSha256,
            checkCancellation,
        )
        if (second !is MetadataCurrentBytesReader.PostCommitRead.Exact ||
            second.actualSize != first.actualSize ||
            !second.actualSha256.equals(first.actualSha256, ignoreCase = true))
            return@withContext Result.Refused("SOURCE_CHANGED_DURING_SNAPSHOT")

        when (MetadataPostSourceFieldPolicy.compare(intent, report)) {
            is MetadataPostSourceFieldPolicy.Verdict.Compared -> Unit
            is MetadataPostSourceFieldPolicy.Verdict.Blocked ->
                return@withContext Result.Refused("SOURCE_FIELDS_DO_NOT_MATCH_INTENT")
        }

        val projected = runCatching {
            report.toSnapshot(target.copy(
                evidenceToken = "me13:sha256:${entry.stageSha256}",
            ))
        }.getOrNull() ?: return@withContext Result.Refused("SNAPSHOT_NOT_VERIFIABLE")
        if (projected.verifiedFields.isEmpty())
            return@withContext Result.Refused("SNAPSHOT_NOT_VERIFIABLE")
        for ((field, observation) in projected.fieldObservations) {
            when (observation) {
                is MetadataFieldObservation.Present ->
                    if (projected.known[field] != observation.values)
                        return@withContext Result.Refused("KNOWN_OBSERVATION_CONFLICT")
                MetadataFieldObservation.VerifiedAbsent ->
                    if (field in projected.known)
                        return@withContext Result.Refused("KNOWN_OBSERVATION_CONFLICT")
                is MetadataFieldObservation.Unverifiable,
                is MetadataFieldObservation.Conflicted ->
                    if (field in projected.known || field in projected.verifiedFields)
                        return@withContext Result.Refused("KNOWN_OBSERVATION_CONFLICT")
            }
        }
        Result.Verified(projected)
    }

    private fun refusalFor(reason: String): String = when (reason) {
        "ACTUAL_SIZE_MISMATCH", "SHA_MISMATCH" -> "SOURCE_SHA_OR_SIZE_MISMATCH"
        "STAMP_CHANGED_DURING_READ" -> "SOURCE_CHANGED_DURING_SNAPSHOT"
        else -> "SOURCE_COLD_READ_FAILED"
    }
}
