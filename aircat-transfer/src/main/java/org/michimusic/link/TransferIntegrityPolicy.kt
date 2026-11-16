package org.michimusic.link

/** Integrity gate for a cached remote audio representation, not a file-name heuristic. */
internal object TransferIntegrityPolicy {
    private val SHA256 = Regex("^[a-fA-F0-9]{64}$")

    fun validSha256(digest: String): Boolean = SHA256.matches(digest)

    fun mayReuse(actualSize: Long, expectedSize: Long, digest: String): Boolean =
        actualSize > 0L && (expectedSize <= 0L || actualSize == expectedSize) &&
            validSha256(digest)
}
