package org.michimusic.mobile.artist.artwork

/** Verified P180 alone is insufficient: later files may be the first legally usable ones.
 * Keep the late-fallback metadata budget finite, deduplicated and stable.
 */
internal object CommonsP180MetadataBudget {
    const val MAX_IMAGEINFO_READS = 8

    fun selected(orderedFiles: List<String>, qidVerifiedFiles: Set<String>): List<String> =
        orderedFiles.asSequence()
            .filter { it in qidVerifiedFiles }
            .distinct()
            .take(MAX_IMAGEINFO_READS)
            .toList()
}
