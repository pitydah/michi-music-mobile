package org.michimusic.mobile.artist.artwork

/**
 * Already-calculated library state supplied by the existing scanner *after* its
 * successful commit. No extra MediaStore/SAF queries may be introduced by this type.
 * A stable associationId is not an album-set fingerprint.
 */
internal data class ArtistArtworkLibraryEntry(
    val libraryId: String,
    val associationId: String,
    val committedMembershipFingerprint: String,
)

/**
 * Never infer that a missing before-snapshot means every artist was just added:
 * initial backfill is a separate, explicit user-approved operation.
 */
internal object ArtistArtworkLibraryDelta {
    fun changedAssociations(
        before: Collection<ArtistArtworkLibraryEntry>?,
        after: Collection<ArtistArtworkLibraryEntry>,
    ): Set<Pair<String, String>> {
        if (before == null) return emptySet()
        // Duplicate conflicting receipts are ambiguous: never let associateBy's
        // last-wins behavior fabricate a changed association. Blank fingerprints
        // are not a committed inventory version and cannot authorize new work.
        fun trustedKeys(rows: Collection<ArtistArtworkLibraryEntry>): Pair<Map<Pair<String, String>, String>, Set<Pair<String, String>>> {
            val fingerprints = HashMap<Pair<String, String>, String>(rows.size)
            val ambiguous = HashSet<Pair<String, String>>()
            rows.forEach { row ->
                if (row.libraryId.isBlank() || row.associationId.isBlank()) return@forEach
                val key = row.libraryId to row.associationId
                if (key in ambiguous) return@forEach
                if (row.committedMembershipFingerprint.isBlank()) {
                    fingerprints.remove(key)
                    ambiguous += key
                    return@forEach
                }
                val existing = fingerprints.putIfAbsent(key, row.committedMembershipFingerprint)
                if (existing != null && existing != row.committedMembershipFingerprint) {
                    fingerprints.remove(key)
                    ambiguous += key
                }
            }
            return fingerprints to ambiguous
        }
        val (baseline, unsafeBefore) = trustedKeys(before)
        val (current, unsafeAfter) = trustedKeys(after)
        return current.asSequence()
            .filter { (key, fingerprint) ->
                key !in unsafeBefore && key !in unsafeAfter && baseline[key] != fingerprint
            }
            .map { it.key }
            .toSet()
    }
}
