package org.michimusic.core.sync

import org.michimusic.core.models.LocalSyncTrackEvidence
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncIdentityVerdict

/** Result of classifying a remote manifest entry against local sync evidence. */
data class SyncIdentityMatch(
    val verdict: SyncIdentityVerdict,
    val matchedSourceId: String = "",
    val evidence: LocalSyncTrackEvidence? = null,
    /** True only when the evidence is strong enough to omit the transfer. */
    val canSkip: Boolean = false,
    val reason: String = "",
)

/**
 * Decides whether a local copy can stand in for a manifest entry.
 *
 * The policy separates three independent facts that must never be collapsed:
 * 1. reference match: same owner and logical track ID;
 * 2. size compatibility: the physical file has the expected byte length;
 * 3. hash verification: a trusted checksum proves the bytes are equal.
 *
 * Only a trusted hash, or a persistent managed-copy verification created by
 * this app for this exact owner/reference, allows skipping a download. A bare
 * size match is never content identity.
 */
object SyncIdentityPolicy {

    fun classify(
        endpointId: String,
        track: ManifestTrack,
        localEvidence: List<LocalSyncTrackEvidence>,
    ): SyncIdentityMatch {
        val sameId = localEvidence.filter { it.trackId == track.trackId }
        val sameOwner = sameId.filter { it.sourceId == endpointId }

        if (sameOwner.isNotEmpty()) {
            val conflicting = sameOwner.firstOrNull { conflicts(track, it) }
            if (conflicting != null) {
                return SyncIdentityMatch(
                    verdict = SyncIdentityVerdict.CONTENT_MISMATCH,
                    matchedSourceId = conflicting.sourceId,
                    evidence = conflicting,
                    reason = "El tamaño o el checksum demuestran que el contenido difiere",
                )
            }

            val hashVerified = sameOwner.firstOrNull { hasVerifiedHash(track, it) }
            if (hashVerified != null) {
                return SyncIdentityMatch(
                    verdict = SyncIdentityVerdict.HASH_VERIFIED,
                    matchedSourceId = hashVerified.sourceId,
                    evidence = hashVerified,
                    canSkip = true,
                    reason = "Checksum compatible verificado sobre el archivo físico",
                )
            }

            val managedReference = sameOwner.firstOrNull {
                it.managedCopyVerified &&
                    it.physicallyVerified &&
                    it.complete &&
                    sizeCompatibleOrUnknown(track, it)
            }
            if (managedReference != null) {
                return SyncIdentityMatch(
                    verdict = SyncIdentityVerdict.REFERENCE_MATCH,
                    matchedSourceId = managedReference.sourceId,
                    evidence = managedReference,
                    canSkip = true,
                    reason = "Copia gestionada y verificada previamente por Michi; archivo sin cambios",
                )
            }

            val sizeOnly = sameOwner.firstOrNull {
                it.physicallyVerified && it.complete && sizeCompatible(track, it)
            }
            if (sizeOnly != null) {
                return SyncIdentityMatch(
                    verdict = SyncIdentityVerdict.SIZE_COMPATIBLE,
                    matchedSourceId = sizeOnly.sourceId,
                    evidence = sizeOnly,
                    reason = "El archivo coincide en tamaño, pero no hay hash que demuestre identidad de contenido",
                )
            }

            val reference = sameOwner.first()
            return SyncIdentityMatch(
                verdict = SyncIdentityVerdict.REFERENCE_MATCH,
                matchedSourceId = reference.sourceId,
                evidence = reference,
                reason = "Existe la referencia del mismo propietario, pero falta verificarla físicamente",
            )
        }

        val foreignOwner = sameId.firstOrNull()
        if (foreignOwner != null) {
            return SyncIdentityMatch(
                verdict = SyncIdentityVerdict.DIFFERENT_SOURCE,
                matchedSourceId = foreignOwner.sourceId,
                evidence = foreignOwner,
                reason = "Otra biblioteca usa el mismo identificador de pista",
            )
        }

        val metadataMatch = localEvidence.firstOrNull { metadataMatches(track, it) }
        if (metadataMatch != null) {
            return SyncIdentityMatch(
                verdict = SyncIdentityVerdict.POSSIBLE_DUPLICATE,
                matchedSourceId = metadataMatch.sourceId,
                evidence = metadataMatch,
                reason = "Coincidencia de metadatos sin identidad de contenido; se descarga",
            )
        }

        return SyncIdentityMatch(
            verdict = SyncIdentityVerdict.UNKNOWN,
            reason = "No hay evidencia local suficiente",
        )
    }

    private fun conflicts(track: ManifestTrack, evidence: LocalSyncTrackEvidence): Boolean {
        val sizeConflict = track.size > 0 && evidence.sizeBytes > 0 && track.size != evidence.sizeBytes
        val checksumConflict = comparableHashes(track, evidence)?.let { (expected, actual) ->
            !expected.equals(actual, ignoreCase = true)
        } == true
        return sizeConflict || checksumConflict
    }

    /**
     * Returns the pair only when both hashes use the same trustworthy algorithm.
     * A manifest checksum with unknown semantics is not comparable.
     */
    private fun comparableHashes(
        track: ManifestTrack,
        evidence: LocalSyncTrackEvidence,
    ): Pair<String, String>? {
        if (!evidence.checksumVerified) return null
        if (track.checksum.isBlank() || evidence.checksum.isBlank()) return null
        if (!evidence.physicallyVerified || !evidence.complete) return null

        val evidenceAlgorithm = evidence.checksumAlgorithm.lowercase()
        val manifestLooksSha256 = isSha256Hex(track.checksum)
        val evidenceLooksSha256 = isSha256Hex(evidence.checksum)
        val compatible = when (evidenceAlgorithm) {
            "sha256" -> manifestLooksSha256 && evidenceLooksSha256
            "" -> manifestLooksSha256 && evidenceLooksSha256
            else -> track.checksum.length == evidence.checksum.length && manifestLooksSha256 && evidenceLooksSha256
        }
        return if (compatible) track.checksum to evidence.checksum else null
    }

    private fun hasVerifiedHash(track: ManifestTrack, evidence: LocalSyncTrackEvidence): Boolean {
        val pair = comparableHashes(track, evidence) ?: return false
        return pair.first.equals(pair.second, ignoreCase = true) &&
            sizeCompatibleOrUnknown(track, evidence)
    }

    private fun sizeCompatible(track: ManifestTrack, evidence: LocalSyncTrackEvidence): Boolean =
        track.size > 0 && evidence.sizeBytes > 0 && track.size == evidence.sizeBytes

    private fun sizeCompatibleOrUnknown(track: ManifestTrack, evidence: LocalSyncTrackEvidence): Boolean =
        track.size <= 0 || sizeCompatible(track, evidence)

    private fun isSha256Hex(value: String): Boolean =
        value.length == 64 && value.all { char ->
            char in '0'..'9' || char.lowercaseChar() in 'a'..'f'
        }

    private fun metadataMatches(track: ManifestTrack, evidence: LocalSyncTrackEvidence): Boolean {
        if (track.title.isBlank() || track.artist.isBlank()) return false
        if (evidence.title.isBlank() || evidence.artist.isBlank()) return false
        if (!track.title.trim().equals(evidence.title.trim(), ignoreCase = true)) return false
        if (!track.artist.trim().equals(evidence.artist.trim(), ignoreCase = true)) return false
        return track.duration <= 0 || evidence.duration <= 0 || track.duration.toLong() == evidence.duration
    }
}
