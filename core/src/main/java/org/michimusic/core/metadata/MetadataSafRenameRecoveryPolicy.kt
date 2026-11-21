package org.michimusic.core.metadata

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/**
 * ME-11O. Reconcile an interrupted SAF rename using a NEW independent parent
 * listing AND a cold, full-file readback. This only classifies observations;
 * it never calls a provider, moves a URI, publishes Room, or grants mutation.
 *
 * A provider's renameDocument() return value alone is never postcheck proof:
 * document IDs can change, providers can adjust names, and null is ambiguous.
 */
object MetadataSafRenameRecoveryPolicy {
    data class Intent(
        val transactionId: String,
        val sourceId: String,
        val trackId: String,
        val beforeUri: String,
        val beforeDocumentId: String,
        val parentUri: String,
        val beforeName: String,
        val requestedName: String,
        val originalSha256: String,
        val originalBytes: Long,
    )

    data class Child(
        val documentId: String,
        val uri: String,
        val displayName: String,
        val size: Long?,
        /** Only populated after explicitly hashing the SAME cold URI. */
        val sha256: String? = null,
        val coldReadVerified: Boolean = false,
    )

    data class Observation(
        val sourceId: String,
        val trackId: String,
        val parentUri: String,
        val completeParentListing: Boolean,
        val sameParentVerified: Boolean,
        val exactDocumentGrantStillCurrent: Boolean,
        val playbackClosed: Boolean,
        val children: List<Child>,
        /** Durable URI returned by renameDocument(), if captured before a crash. */
        val returnedDocumentUri: String? = null,
    )

    enum class Reason {
        INVALID_INTENT, WRONG_OWNER, PARENT_UNVERIFIED, GRANT_UNVERIFIED,
        PLAYBACK_ACTIVE, AMBIGUOUS_LISTING, NAME_COLLISION,
        NO_MATCH, BOTH_NAMES_PRESENT, SOURCE_CHANGED, DOCUMENT_ID_CONFLICT,
        URI_AUTHORITY_CHANGED, POSTREADBACK_MISSING, UNEXPECTED_NAME, PROVIDER_RESULT_MISSING,
    }

    sealed interface Verdict {
        data class Uncertain(val reason: Reason) : Verdict
        /** Original document exists unmodified; rename is NOT considered applied. */
        data class OriginalVerified(val uri: String, val documentId: String) : Verdict
        /** A postcheck receipt, NOT authority to publish to Room or clean a journal. */
        data class RenamedVerified(val uri: String, val documentId: String) : Verdict
    }

    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val digest = Regex("[a-f0-9]{64}")
    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun authority(uri: String): String? = try {
        val parsed = URI(uri)
        if (parsed.scheme != "content" || parsed.rawAuthority.isNullOrBlank()) null
        else parsed.rawAuthority
    } catch (_: Exception) { null }

    fun reconcile(intent: Intent, observation: Observation): Verdict {
        fun uncertain(reason: Reason) = Verdict.Uncertain(reason)
        val originalAuthority = authority(intent.beforeUri)
        if (!uuid.matches(intent.transactionId) || intent.trackId.isBlank() ||
            intent.beforeDocumentId.isBlank() || intent.beforeName.isBlank() ||
            intent.requestedName.isBlank() || intent.beforeName == intent.requestedName ||
            intent.beforeName.length > 255 || intent.requestedName.length > 255 ||
            normalized(intent.beforeName) == normalized(intent.requestedName) ||
            intent.beforeName.contains('/') || intent.requestedName.contains('/') ||
            !digest.matches(intent.originalSha256) || intent.originalBytes <= 0 ||
            originalAuthority == null || authority(intent.parentUri) != originalAuthority)
            return uncertain(Reason.INVALID_INTENT)
        if (observation.sourceId != intent.sourceId || observation.trackId != intent.trackId)
            return uncertain(Reason.WRONG_OWNER)
        if (!observation.completeParentListing || !observation.sameParentVerified ||
            observation.parentUri != intent.parentUri)
            return uncertain(Reason.PARENT_UNVERIFIED)
        if (!observation.exactDocumentGrantStillCurrent)
            return uncertain(Reason.GRANT_UNVERIFIED)
        if (!observation.playbackClosed)
            return uncertain(Reason.PLAYBACK_ACTIVE)
        val children = observation.children
        if (children.size > 4096 || children.any {
                it.documentId.isBlank() || it.uri.isBlank() || it.displayName.isBlank()
            } || children.map { it.documentId }.toSet().size != children.size ||
            children.map { it.uri }.toSet().size != children.size)
            return uncertain(Reason.AMBIGUOUS_LISTING)
        if (children.any { authority(it.uri) != originalAuthority })
            return uncertain(Reason.URI_AUTHORITY_CHANGED)
        val before = children.filter { normalized(it.displayName) == normalized(intent.beforeName) }
        val after = children.filter { normalized(it.displayName) == normalized(intent.requestedName) }
        if (before.size > 1 || after.size > 1) return uncertain(Reason.NAME_COLLISION)
        if (before.isNotEmpty() && after.isNotEmpty())
            return uncertain(Reason.BOTH_NAMES_PRESENT)

        val current = when {
            after.size == 1 -> after.single()
            before.size == 1 -> before.single()
            else -> return uncertain(Reason.NO_MATCH)
        }
        val renamed = after.isNotEmpty()
        // An ID left behind under a third name after purported ID migration
        // defeats the one-document identity proof, even when bytes match.
        if (renamed && children.any {
                it.documentId == intent.beforeDocumentId && it.documentId != current.documentId
            }) return uncertain(Reason.DOCUMENT_ID_CONFLICT)
        if (renamed && current.documentId != intent.beforeDocumentId &&
            observation.returnedDocumentUri != current.uri)
            return uncertain(Reason.PROVIDER_RESULT_MISSING)
        if (renamed && current.displayName != intent.requestedName)
            return uncertain(Reason.UNEXPECTED_NAME)
        if (!renamed && (current.displayName != intent.beforeName ||
            current.documentId != intent.beforeDocumentId || current.uri != intent.beforeUri))
            return uncertain(Reason.DOCUMENT_ID_CONFLICT)
        // Even a matching name and size are not enough: require a full cold hash.
        if (!current.coldReadVerified || current.sha256 == null || current.size == null)
            return uncertain(Reason.POSTREADBACK_MISSING)
        if (current.size != intent.originalBytes || current.sha256 != intent.originalSha256)
            return uncertain(Reason.SOURCE_CHANGED)
        // ID or URI changes are allowed for a rename, but both are taken from a
        // freshly observed parent listing, never guessed from renameDocument().
        return if (renamed) Verdict.RenamedVerified(current.uri, current.documentId)
        else Verdict.OriginalVerified(current.uri, current.documentId)
    }
}
