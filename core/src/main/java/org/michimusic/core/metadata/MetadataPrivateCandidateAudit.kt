package org.michimusic.core.metadata

import java.io.File
import java.util.concurrent.CancellationException

/**
 * ME-10J: offline, on-demand verification of app-private session BYTES only.
 * A matching hash never certifies selected tag diffs, provenance of an Android
 * provider, restoration safety or permission to replace a music file.
 * The Android coordinator must validate directory ownership and read receipt
 * bytes with a small cap before calling this pure boundary.
 */
object MetadataPrivateCandidateAudit {
    private val sha256 = Regex("[0-9a-f]{64}")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private const val MAX_PRIVATE_BYTES = 256L * 1024L * 1024L
    const val MAX_RECEIPT_BYTES = 512L
    enum class Reason {
        INVALID_RECEIPT, MISSING_OR_UNSAFE_FILES, ORIGINAL_HASH_MISMATCH,
        CANDIDATE_HASH_MISMATCH, AUDIO_HASH_MISMATCH, FORMAT_MISMATCH,
        UNSUPPORTED_MEDIA, IO_UNAVAILABLE, OWNER_MISMATCH, LEGACY_OWNER_UNBOUND,
    }
    sealed interface Result {
        /** The named private files match a bounded receipt, nothing more. */
        data class HashesMatchPrivateOnly(
            val originalBytes: Long, val candidateBytes: Long,
            val originalSha256: String, val candidateSha256: String,
            val audioSha256: String,
            val canCommit: Boolean = false,
            /** True ONLY after comparing against the active MetadataTarget. */
            val ownerMatched: Boolean = false,
            /** V1 receipts are legacy/unbound even when their bytes match. */
            val ownerBound: Boolean = false,
        ) : Result
        data class Refused(val reason: Reason) : Result
    }
    private data class Manifest(
        val sourceSha: String, val candidateSha: String,
        val audioSha: String, val candidateBytes: Long,
        val ownerSha: String?,
    )
    private fun parse(sessionId: String, receipt: String): Manifest? {
        if (!uuid.matches(sessionId) || receipt.length > MAX_RECEIPT_BYTES ||
            !receipt.endsWith("\n") || '\r' in receipt || '\u0000' in receipt) return null
        val lines = receipt.dropLast(1).split('\n')
        val v1 = lines.size == 7 && lines[0] == "ME10H_PRIVATE_PREVIEW_V1"
        val v2 = lines.size == 8 && lines[0] == "ME10K_PRIVATE_PREVIEW_V2"
        if ((!v1 && !v2) || lines[1] != sessionId ||
            lines.last() != "CAN_COMMIT=false" ||
            !sha256.matches(lines[2]) || !sha256.matches(lines[3]) ||
            !sha256.matches(lines[4])) return null
        val size = lines[5].toLongOrNull() ?: return null
        if (size !in 20..MAX_PRIVATE_BYTES || lines[5] != size.toString()) return null
        val owner = if (v2) lines[6].removePrefix("OWNER_SHA256=") else null
        if (v2 && (!lines[6].startsWith("OWNER_SHA256=") ||
                owner == null || !sha256.matches(owner))) return null
        return Manifest(lines[2], lines[3], lines[4], size, owner)
    }

    fun verify(
        sessionId: String, original: File, candidate: File, receipt: String,
        expectedOwnerSha256: String? = null,
        checkCancelled: () -> Unit = {},
    ): Result {
        val manifest = parse(sessionId, receipt)
            ?: return Result.Refused(Reason.INVALID_RECEIPT)
        if (expectedOwnerSha256 != null) {
            if (!sha256.matches(expectedOwnerSha256))
                return Result.Refused(Reason.INVALID_RECEIPT)
            if (manifest.ownerSha == null)
                return Result.Refused(Reason.LEGACY_OWNER_UNBOUND)
            if (manifest.ownerSha != expectedOwnerSha256)
                return Result.Refused(Reason.OWNER_MISMATCH)
        }
        if (original == candidate || original.parentFile != candidate.parentFile ||
            original.name != "original.bin" || candidate.name != "candidate.bin" ||
            !original.isFile || !candidate.isFile ||
            candidate.length() != manifest.candidateBytes)
            return Result.Refused(Reason.MISSING_OR_UNSAFE_FILES)
        return try {
            checkCancelled()
            val before = MetadataPrivateBundleStage.auditDigests(original, checkCancelled)
            if (before.fullSha256 != manifest.sourceSha)
                return Result.Refused(Reason.ORIGINAL_HASH_MISMATCH)
            val after = MetadataPrivateBundleStage.auditDigests(candidate, checkCancelled)
            if (after.bytes != manifest.candidateBytes ||
                after.fullSha256 != manifest.candidateSha)
                return Result.Refused(Reason.CANDIDATE_HASH_MISMATCH)
            if (before.format != after.format) return Result.Refused(Reason.FORMAT_MISMATCH)
            if (before.audioSha256 != manifest.audioSha || after.audioSha256 != manifest.audioSha)
                return Result.Refused(Reason.AUDIO_HASH_MISMATCH)
            checkCancelled()
            Result.HashesMatchPrivateOnly(before.bytes, after.bytes, manifest.sourceSha,
                manifest.candidateSha, manifest.audioSha,
                ownerMatched = expectedOwnerSha256 != null,
                ownerBound = manifest.ownerSha != null)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: MetadataPrivateBundleStage.Rejected) { Result.Refused(Reason.UNSUPPORTED_MEDIA) }
          catch (_: Exception) { Result.Refused(Reason.IO_UNAVAILABLE) }
    }
}
