package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataProviderRecoveryProfile
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReadAccessPolicy
import org.michimusic.core.metadata.MetadataWriteProfileRegistry

/**
 * ME-10M. Source-byte transport only. Intentionally NOT called from Compose,
 * the preparation path or recovery. Both independently checked certification
 * gates are currently hard-false. The adapter exists to enable OpenCode to
 * inspect and certify the source IO sequence, not to enable writes today.
 *
 * SAF/MediaStore are not assumed atomic. Any exception after open may leave
 * UNKNOWN_BYTES; the caller MUST keep the durable original backup and journal.
 */
internal class MetadataProviderMutationTransport(private val context: Context) {
    enum class Refusal {
        UNCERTIFIED, NOT_MUTATING, INVALID_CANDIDATE, INVALID_SOURCE_URI,
        CANDIDATE_CHANGED, PROVIDER_UNAVAILABLE, SOURCE_WRITE_UNCERTAIN,
    }
    sealed interface Result {
        data class Refused(val reason: Refusal) : Result
        /** Source may have changed; never treat as a success receipt. */
        data object UnknownAfterOpeningSource : Result
        /** Closing the FD does not certify durable or semantically correct tags. */
        data object BytesSentRequireColdReadback : Result
    }

    private val sha = Regex("[a-f0-9]{64}")
    // The stager, opaque auditor and cold source reader all support <= 1 GiB.
    // Keep this streaming transport aligned; never allocate the file in RAM.
    private val maxBytes = 1024L * 1024L * 1024L

    fun overwrite(
        entry: MetadataRecoveryJournalV1.Entry,
        candidate: File,
        profile: MetadataProviderRecoveryProfile.Evidence,
        checkCancellation: () -> Unit = {},
    ): Result = writeExact(entry, candidate, profile, entry.stageBytes,
        entry.stageSha256, MetadataRecoveryPhase.MUTATING, checkCancellation, null)

    fun overwriteWithPermit(
        entry: MetadataRecoveryJournalV1.Entry,
        candidate: File,
        permit: MetadataWritePermit,
        checkCancellation: () -> Unit = {},
    ): Result = writeExact(entry, candidate, null, entry.stageBytes,
        entry.stageSha256, MetadataRecoveryPhase.MUTATING, checkCancellation,
        ScopedAuthorization.Write(permit))

    /**
     * ME-10R: rollback bytes can ONLY come from the journal-bound verified backup,
     * in a durable ROLLBACK_PENDING phase. Source ownership, candidate-state
     * fingerprint and user confirmation are enforced in the calling coordinator.
     * This path remains unreachable while either certification gate is false.
     */
    fun restore(
        entry: MetadataRecoveryJournalV1.Entry,
        originalBackup: File,
        profile: MetadataProviderRecoveryProfile.Evidence,
        checkCancellation: () -> Unit = {},
    ): Result = writeExact(entry, originalBackup, profile, entry.expectedBytes,
        entry.originalSha256, MetadataRecoveryPhase.ROLLBACK_PENDING, checkCancellation, null)

    /**
     * ME-13 S5: exact MediaStore MP3 restore behind a typed permit. The permit
     * replaces only the two global review booleans for this exact transaction;
     * the backup hash, provider family and phase are revalidated here too.
     */
    fun restoreWithPermit(
        entry: MetadataRecoveryJournalV1.Entry,
        originalBackup: File,
        permit: MetadataRestorePermit,
        checkCancellation: () -> Unit = {},
    ): Result = writeExact(entry, originalBackup, null, entry.expectedBytes,
        entry.originalSha256, MetadataRecoveryPhase.ROLLBACK_PENDING, checkCancellation,
        ScopedAuthorization.Restore(permit))

    private sealed interface ScopedAuthorization {
        data class Write(val permit: MetadataWritePermit) : ScopedAuthorization
        data class Restore(val permit: MetadataRestorePermit) : ScopedAuthorization
    }

    private fun writeExact(
        entry: MetadataRecoveryJournalV1.Entry,
        candidate: File,
        profile: MetadataProviderRecoveryProfile.Evidence?,
        expectedBytes: Long,
        expectedSha256: String,
        requiredPhase: MetadataRecoveryPhase,
        checkCancellation: () -> Unit,
        scoped: ScopedAuthorization?,
    ): Result {
        val permitted = when (scoped) {
            is ScopedAuthorization.Write -> requiredPhase == MetadataRecoveryPhase.MUTATING &&
                scoped.permit.matches(entry) && scoped.permit.stageBytes == expectedBytes &&
                scoped.permit.candidateSha256 == expectedSha256
            is ScopedAuthorization.Restore ->
                requiredPhase == MetadataRecoveryPhase.ROLLBACK_PENDING &&
                    scoped.permit.matches(entry) &&
                    scoped.permit.originalBytes == expectedBytes &&
                    scoped.permit.originalSha256 == expectedSha256
            null -> false
        }
        // No caller can promote a V2 preview receipt or bypass these static gates.
        if (!permitted && (!MetadataCommitCertification.mayMutateOriginal() ||
                profile == null ||
                !MetadataProviderRecoveryProfile.permitsSourceMutation(profile)))
            return Result.Refused(Refusal.UNCERTIFIED)
        if (entry.phase != requiredPhase)
            return Result.Refused(Refusal.NOT_MUTATING)
        if (expectedBytes !in 12..maxBytes || !sha.matches(expectedSha256) ||
            !Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            candidate.length() != expectedBytes)
            return Result.Refused(Refusal.INVALID_CANDIDATE)
        val uri = try { Uri.parse(entry.uri) }
            catch (_: Exception) { return Result.Refused(Refusal.INVALID_SOURCE_URI) }
        if (uri.scheme != "content" || uri.authority.isNullOrBlank())
            return Result.Refused(Refusal.INVALID_SOURCE_URI)
        // Revalidate the provider family in this innermost boundary too; a
        // guessed content:// authority or a mismatched recovery profile fails.
        val sourceKind = MetadataReadAccessPolicy.classify(entry.sourceId,
            uri.scheme, uri.authority)
        val scopeMatches = when (scoped) {
            is ScopedAuthorization.Write ->
                MetadataWriteProfileRegistry.byId(scoped.permit.profileId)?.provider ==
                    MetadataSourceProviderIdentity.classify(
                        entry.sourceId, uri.scheme, uri.authority,
                    )?.profileProvider
            is ScopedAuthorization.Restore ->
                MetadataWriteProfileRegistry.byId(scoped.permit.profileId)?.provider ==
                    MetadataSourceProviderIdentity.classify(
                        entry.sourceId, uri.scheme, uri.authority,
                    )?.profileProvider &&
                    scoped.permit.container == entry.format
            null -> when (sourceKind) {
                MetadataReadAccessPolicy.Kind.MEDIASTORE ->
                    profile?.provider == MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO
                MetadataReadAccessPolicy.Kind.SAF_LOCAL ->
                    profile?.provider == MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT
                MetadataReadAccessPolicy.Kind.REJECT -> false
            }
        }
        if (!scopeMatches) return Result.Refused(Refusal.INVALID_SOURCE_URI)

        // Hash before the first potentially destructive provider call. Android
        // providers and other processes are NOT protected by our process mutex.
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileChannel.open(candidate.toPath(), StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
                var count = 0L
                while (true) {
                    checkCancellation()
                    buffer.clear()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) return Result.Refused(Refusal.CANDIDATE_CHANGED)
                    count += n
                    if (count > expectedBytes) return Result.Refused(Refusal.CANDIDATE_CHANGED)
                    buffer.flip()
                    digest.update(buffer)
                }
                if (count != expectedBytes ||
                    digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') } != expectedSha256)
                    return Result.Refused(Refusal.CANDIDATE_CHANGED)
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { return Result.Refused(Refusal.CANDIDATE_CHANGED) }

        // After a provider FD is opened the outcome is UNKNOWN on *all* errors.
        // "rwt" is never assumed to truncate atomically; cold readback is vital.
        var providerOpenAttempted = false
        return try {
            checkCancellation()
            // Some providers may change bytes while processing an open request,
            // even if the request throws or returns no descriptor.
            providerOpenAttempted = true
            val fd = context.contentResolver.openFileDescriptor(uri, "rwt")
                ?: return Result.UnknownAfterOpeningSource
            fd.use { descriptor ->
                FileChannel.open(candidate.toPath(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use { input ->
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
                        var count = 0L
                        val sentHash = MessageDigest.getInstance("SHA-256")
                        while (true) {
                            checkCancellation()
                            buffer.clear()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) error("CANDIDATE_READ_STALLED")
                            count += n
                            if (count > expectedBytes) error("CANDIDATE_SIZE_CHANGED")
                            buffer.flip()
                            sentHash.update(buffer.asReadOnlyBuffer())
                            var stalledWrites = 0
                            while (buffer.hasRemaining()) {
                                checkCancellation()
                                val written = output.channel.write(buffer)
                                if (written < 0 || (written == 0 && ++stalledWrites > 8))
                                    error("SOURCE_WRITE_STALLED")
                                if (written > 0) stalledWrites = 0
                            }
                        }
                        if (count != expectedBytes ||
                            sentHash.digest().joinToString("") {
                                (it.toInt() and 255).toString(16).padStart(2,'0')
                            } != expectedSha256) error("CANDIDATE_BYTES_CHANGED")
                        output.fd.sync()
                    }
                }
            }
            Result.BytesSentRequireColdReadback
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) {
            if (providerOpenAttempted) Result.UnknownAfterOpeningSource
            else Result.Refused(Refusal.PROVIDER_UNAVAILABLE)
        }
    }
}
