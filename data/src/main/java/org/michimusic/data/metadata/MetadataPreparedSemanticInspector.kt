package org.michimusic.data.metadata

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataCandidateSemanticPolicy
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataResourceSemanticPolicy
import org.michimusic.core.metadata.MetadataAudioContainer

/**
 * ME-10O: read-only, explicit inspection of two private journal files, bound
 * to the hashes in the V1 intent. Never opens source URI or updates Room.
 *
 * NOFOLLOW applies to the final entry; the journal's validated private root
 * and single-flight lock are additional requirements. This is not a cross-
 * process CAS or an atomicity guarantee for third-party Android providers.
 */
internal object MetadataPreparedSemanticInspector {
    sealed interface Inspection {
        data class Compared(
            val verdict: MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch,
            val resources: MetadataResourceSemanticPolicy.Verdict.ResourcesMatch,
        ) : Inspection {
            val permitsSourceMutation: Boolean get() = false
        }
        data class Refused(val reason: Refusal,
            val semantic: MetadataCandidateSemanticPolicy.Verdict.Refused? = null) : Inspection
    }
    enum class Refusal { INVALID_INTENT, PRIVATE_FILE_UNREADABLE, PRIVATE_HASH_MISMATCH, SEMANTIC_MISMATCH }
    private const val BUFFER = 64 * 1024

    private data class PhysicalRead(
        val report: MetadataFieldReadReport,
        val resources: MetadataArtworkLyricsProbe.Snapshot?,
    )
    private class BadPrivateHash : Exception()

    fun inspectFiles(
        original: File,
        candidate: File,
        intent: MetadataCommitIntentManifest.Receipt,
        checkCancellation: () -> Unit = {},
    ): Inspection {
        if (!MetadataCommitIntentManifest.valid(intent))
            return Inspection.Refused(Refusal.INVALID_INTENT)
        val before = try {
            readAndVerify(
                original, intent.originalBytes, intent.originalSha256,
                intent.container in setOf(
                    MetadataAudioContainer.ID3_V23,
                    MetadataAudioContainer.ID3_V24,
                    MetadataAudioContainer.FLAC,
                    MetadataAudioContainer.MP4_M4A,
                ),
                checkCancellation,
            )
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: BadPrivateHash) {
            return Inspection.Refused(Refusal.PRIVATE_HASH_MISMATCH)
        } catch (_: Exception) {
            return Inspection.Refused(Refusal.PRIVATE_FILE_UNREADABLE)
        }
        val after = try {
            readAndVerify(
                candidate, intent.candidateBytes, intent.candidateSha256,
                intent.container in setOf(
                    MetadataAudioContainer.ID3_V23,
                    MetadataAudioContainer.ID3_V24,
                    MetadataAudioContainer.FLAC,
                    MetadataAudioContainer.MP4_M4A,
                ),
                checkCancellation,
            )
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: BadPrivateHash) {
            return Inspection.Refused(Refusal.PRIVATE_HASH_MISMATCH)
        } catch (_: Exception) {
            return Inspection.Refused(Refusal.PRIVATE_FILE_UNREADABLE)
        }
        val fields = when (val verdict =
            MetadataCandidateSemanticPolicy.compare(intent, before.report, after.report)
        ) {
            is MetadataCandidateSemanticPolicy.Verdict.Refused ->
                return Inspection.Refused(Refusal.SEMANTIC_MISMATCH, verdict)
            is MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch -> verdict
        }
        val resourceVerdict = if (intent.container in setOf(
                MetadataAudioContainer.ID3_V23,
                MetadataAudioContainer.ID3_V24,
                MetadataAudioContainer.FLAC,
                MetadataAudioContainer.MP4_M4A,
            )
        ) MetadataResourceSemanticPolicy.compare(
            intent,
            before.resources ?: return Inspection.Refused(Refusal.SEMANTIC_MISMATCH),
            after.resources ?: return Inspection.Refused(Refusal.SEMANTIC_MISMATCH),
        ) else MetadataResourceSemanticPolicy.Verdict.ResourcesMatch(0)
        return when (val resources = resourceVerdict) {
            is MetadataResourceSemanticPolicy.Verdict.Refused ->
                Inspection.Refused(Refusal.SEMANTIC_MISMATCH)
            is MetadataResourceSemanticPolicy.Verdict.ResourcesMatch ->
                Inspection.Compared(fields, resources)
        }
    }

    private fun readAndVerify(
        file: File,
        expectedBytes: Long,
        expectedSha256: String,
        observeResources: Boolean,
        checkCancellation: () -> Unit,
    ): PhysicalRead {
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw IllegalStateException("PRIVATE_NOT_REGULAR")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != expectedBytes) throw BadPrivateHash()
            // The parser issues positional reads from this exact, read-only FD.
            val report = MetadataColdFieldParser.observe(channel)
            // Hash the same descriptor AFTER parsing, not a potentially replaced
            // path. A new open here would undermine the check.
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(BUFFER)
            var at = 0L
            while (at < expectedBytes) {
                checkCancellation()
                buffer.clear()
                buffer.limit(minOf(BUFFER.toLong(), expectedBytes - at).toInt())
                val read = channel.read(buffer, at)
                if (read <= 0) throw BadPrivateHash()
                at += read
                buffer.flip()
                digest.update(buffer)
            }
            if (channel.size() != expectedBytes) throw BadPrivateHash()
            val actualHash = digest.digest().joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
            if (actualHash != expectedSha256) throw BadPrivateHash()
            val resources = if (observeResources)
                runCatching { MetadataPrivateBundleStage.observeChannel(channel) }.getOrNull()
            else null
            return PhysicalRead(report, resources)
        }
    }
}
