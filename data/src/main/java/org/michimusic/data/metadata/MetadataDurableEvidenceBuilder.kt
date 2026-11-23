package org.michimusic.data.metadata

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

/**
 * ME-13 P0-A: rebuild the original snapshot from durable private evidence.
 *
 * The editor draft is deliberately not an input. A recovery after a process kill
 * may have no Compose state, a different track, or a cleared review. This builder
 * proves the private backup/stage still match the durable intent, that the owner
 * digest belongs to the exact source identity, and that every changed field is
 * exactly the "before" value recorded in the intent. The returned snapshot is
 * built only from fields the cold parser actually verified.
 */
internal object MetadataDurableEvidenceBuilder {
    sealed interface Result {
        data class Rebuilt(
            val original: MetadataSnapshot,
            val backup: MetadataFieldReadReport,
            val stage: MetadataFieldReadReport,
        ) : Result
        data class Refused(val code: String) : Result
    }

    fun rebuild(
        journal: MetadataRecoveryJournalV1,
        entry: MetadataRecoveryJournalV1.Entry,
        intent: MetadataCommitIntentManifest.Receipt,
        target: MetadataTarget,
    ): Result {
        if (!MetadataCommitIntentManifest.valid(intent) ||
            intent.transactionId != entry.id ||
            intent.originalSha256 != entry.originalSha256 ||
            intent.candidateSha256 != entry.stageSha256 ||
            intent.originalBytes != entry.expectedBytes ||
            intent.candidateBytes != entry.stageBytes)
            return Result.Refused("INTENT_IDENTITY_MISMATCH")
        if (target.sourceId != entry.sourceId || target.trackId != entry.trackId ||
            target.uri != entry.uri || target.evidenceToken.isBlank())
            return Result.Refused("TARGET_IDENTITY_MISMATCH")
        if (MetadataPrivateSessionIdentity.digest(target) != intent.ownerSha256)
            return Result.Refused("OWNER_EVIDENCE_MISMATCH")
        val backup = journal.backup(entry)
        val stage = journal.stage(entry)
        if (!privateRegular(backup) || !privateRegular(stage))
            return Result.Refused("PRIVATE_COPY_UNREADABLE")
        when (val inspected = MetadataPreparedSemanticInspector.inspectFiles(
            backup, stage, intent)) {
            is MetadataPreparedSemanticInspector.Inspection.Compared -> Unit
            is MetadataPreparedSemanticInspector.Inspection.Refused -> {
                val field = inspected.semantic?.field?.name.orEmpty()
                return Result.Refused("PRIVATE_SEMANTIC_MISMATCH:" +
                    inspected.reason.name + if (field.isEmpty()) "" else ":$field")
            }
        }
        val before = parse(backup) ?: return Result.Refused("BACKUP_PARSE_FAILED")
        val after = parse(stage) ?: return Result.Refused("STAGE_PARSE_FAILED")
        val original = try { before.toSnapshot(target) }
            catch (_: Exception) { return Result.Refused("BACKUP_FIELDS_UNVERIFIED") }
        for (change in intent.changes) {
            val beforeValues = values(before, change.field)
                ?: return Result.Refused("BACKUP_FIELD_UNVERIFIED")
            val afterValues = values(after, change.field)
                ?: return Result.Refused("STAGE_FIELD_UNVERIFIED")
            if (MetadataCommitIntentManifest.valuesDigest(beforeValues) != change.beforeSha256 ||
                MetadataCommitIntentManifest.valuesDigest(afterValues) != change.afterSha256)
                return Result.Refused("FIELD_DIGEST_MISMATCH")
        }
        return Result.Rebuilt(original, before, after)
    }

    private fun privateRegular(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())

    private fun parse(file: File): MetadataFieldReadReport? = try {
        FileChannel.open(file.toPath(), StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS).use { MetadataColdFieldParser.observe(it) }
    } catch (_: Exception) { null }

    private fun values(report: MetadataFieldReadReport,
                       field: org.michimusic.core.metadata.MetadataField): List<String>? =
        when (val observation = report.observations[field]) {
            is MetadataFieldObservation.Present -> observation.values
            MetadataFieldObservation.VerifiedAbsent -> emptyList()
            else -> null
        }
}
