package org.michimusic.core.metadata

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/**
 * ME-11P: independent, bounded, two-listing evidence for SAF rename recovery.
 * No Android/provider I/O, mutation, permission grant or Room publication.
 * A provider must be independently certified to enumerate this particular parent
 * completely; absence of EXTRA_LOADING alone is not evidence of completeness.
 */
object MetadataSafRenameReadbackEvidence {
    data class Row(
        val documentId: String,
        val uri: String,
        val name: String,
        val declaredBytes: Long?,
    )
    data class ParentScan(
        val parentUri: String,
        val completelyEnumerated: Boolean,
        val rows: List<Row>,
    )
    data class ColdProbe(
        val uri: String,
        val bytesRead: Long,
        val sha256: String,
        val verifiedEndOfStream: Boolean,
    )
    enum class Rejection {
        WRONG_OWNER, PROVIDER_NOT_CERTIFIED, WRONG_PARENT, NO_GRANT,
        PLAYBACK_ACTIVE, INCOMPLETE_LISTING, TOO_MANY_CHILDREN,
        INVALID_ROW, AMBIGUOUS_LISTING, CHANGED_DURING_READBACK,
        MISSING_COLD_PROBE, INVALID_COLD_PROBE,
    }
    sealed interface Result {
        data class Rejected(val reason: Rejection) : Result
        data class Ready(val observation: MetadataSafRenameRecoveryPolicy.Observation) : Result
    }
    private val sha = Regex("[a-f0-9]{64}")
    private fun folded(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun authority(value: String): String? = try {
        val uri = URI(value)
        if (uri.scheme != "content" || uri.rawAuthority.isNullOrBlank()) null else uri.rawAuthority
    } catch (_: Exception) { null }

    fun assemble(
        intent: MetadataSafRenameRecoveryPolicy.Intent,
        sourceId: String,
        trackId: String,
        first: ParentScan,
        second: ParentScan,
        probes: List<ColdProbe>,
        providerListingCertified: Boolean,
        parentDocumentVerified: Boolean,
        persistedTreeReadGrant: Boolean,
        playbackClosed: Boolean,
    ): Result {
        fun no(reason: Rejection): Result = Result.Rejected(reason)
        if (sourceId != intent.sourceId || trackId != intent.trackId)
            return no(Rejection.WRONG_OWNER)
        if (!providerListingCertified) return no(Rejection.PROVIDER_NOT_CERTIFIED)
        if (first.parentUri != intent.parentUri || second.parentUri != intent.parentUri ||
            !parentDocumentVerified || authority(first.parentUri) != authority(intent.beforeUri))
            return no(Rejection.WRONG_PARENT)
        if (!persistedTreeReadGrant) return no(Rejection.NO_GRANT)
        if (!playbackClosed) return no(Rejection.PLAYBACK_ACTIVE)
        if (!first.completelyEnumerated || !second.completelyEnumerated)
            return no(Rejection.INCOMPLETE_LISTING)
        if (first.rows.size > 4096 || second.rows.size > 4096)
            return no(Rejection.TOO_MANY_CHILDREN)
        val originalAuthority = authority(intent.beforeUri)
        fun validRows(rows: List<Row>) = originalAuthority != null && rows.all {
            it.documentId.isNotBlank() && it.uri.isNotBlank() && it.name.isNotBlank() &&
                it.name.length <= 255 && authority(it.uri) == originalAuthority &&
                (it.declaredBytes == null || it.declaredBytes > 0L)
        }
        if (!validRows(first.rows) || !validRows(second.rows)) return no(Rejection.INVALID_ROW)
        fun unique(rows: List<Row>): Boolean = rows.map { it.documentId }.toSet().size == rows.size &&
            rows.map { it.uri }.toSet().size == rows.size
        if (!unique(first.rows) || !unique(second.rows)) return no(Rejection.AMBIGUOUS_LISTING)
        // Ignore provider order but NEVER silently accept new, deleted, or modified rows.
        val stableFirst = first.rows.sortedWith(compareBy<Row>({ it.documentId }, { it.uri }))
        val stableSecond = second.rows.sortedWith(compareBy<Row>({ it.documentId }, { it.uri }))
        if (stableFirst != stableSecond) return no(Rejection.CHANGED_DURING_READBACK)
        val candidates = second.rows.filter {
            folded(it.name) == folded(intent.beforeName) ||
                folded(it.name) == folded(intent.requestedName)
        }
        if (probes.map { it.uri }.distinct().size != probes.size ||
            probes.size != candidates.size ||
            probes.map { it.uri }.toSet() != candidates.map { it.uri }.toSet())
            return no(Rejection.MISSING_COLD_PROBE)
        val byUri = probes.associateBy { it.uri }
        if (candidates.any { child ->
                val probe = byUri[child.uri] ?: return@any true
                !probe.verifiedEndOfStream || probe.bytesRead <= 0L ||
                    probe.bytesRead > 1024L * 1024L * 1024L ||
                    !sha.matches(probe.sha256) ||
                    (child.declaredBytes != null && child.declaredBytes != probe.bytesRead)
            }) return no(Rejection.INVALID_COLD_PROBE)
        val children = second.rows.map { row ->
            val probe = byUri[row.uri]
            MetadataSafRenameRecoveryPolicy.Child(
                documentId = row.documentId, uri = row.uri,
                displayName = row.name, size = probe?.bytesRead ?: row.declaredBytes,
                sha256 = probe?.sha256,
                coldReadVerified = probe?.verifiedEndOfStream == true,
            )
        }
        return Result.Ready(MetadataSafRenameRecoveryPolicy.Observation(
            sourceId = sourceId, trackId = trackId, parentUri = intent.parentUri,
            completeParentListing = true, sameParentVerified = true,
            exactDocumentGrantStillCurrent = true, playbackClosed = true,
            children = children,
        ))
    }
}
