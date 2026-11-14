package org.michimusic.data.intelligence

/**
 * MI-25: a validated original backup alone does not authorize overwriting a provider.
 * Re-read the destination bytes; unknown contents might belong to another editor.
 * This classifier is pure, works with SHA-256 fingerprints, and never reads audio by itself.
 */
internal object MichiGenreRecoverySafetyPolicy {
    enum class CurrentBytes { ORIGINAL, STAGED, UNKNOWN, UNREADABLE }

    private val sha256 = Regex("[0-9a-f]{64}")

    /** Second consent is valid for ONE exact unexpected byte fingerprint, never any file. */
    fun permitsUnknownOverwrite(
        entry: MichiGenreRecoveryJournal.Entry,
        current: Pair<Long, String>?,
        secondConsent: Boolean,
        approvedBytes: Long?,
        approvedSha256: String?,
    ): Boolean = secondConsent &&
        classify(entry, current) == CurrentBytes.UNKNOWN && current != null &&
        approvedBytes != null && approvedBytes >= 0 &&
        approvedSha256 != null && sha256.matches(approvedSha256) &&
        current == (approvedBytes to approvedSha256)

    fun classify(
        entry: MichiGenreRecoveryJournal.Entry,
        current: Pair<Long, String>?,
    ): CurrentBytes = when {
        current == null -> CurrentBytes.UNREADABLE
        entry.originalBytes > 0 && current.first == entry.originalBytes &&
            current.second == entry.originalSha256 -> CurrentBytes.ORIGINAL
        entry.stagedBytes > 0 && current.first == entry.stagedBytes &&
            current.second == entry.stagedSha256 -> CurrentBytes.STAGED
        else -> CurrentBytes.UNKNOWN
    }
}
