package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * ME-10K: stable, length-framed SHA-256 owner fingerprint for PRIVATE previews.
 * This is a comparison key, not authentication, a write permit or a provider CAS.
 * Raw track identifiers, URIs and metadata values never enter the receipt.
 */
object MetadataPrivateSessionIdentity {
    private const val MAX_PART_BYTES = 8192
    private const val DOMAIN = "MICHI_METADATA_PRIVATE_OWNER_V2"

    fun digest(target: MetadataTarget): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (value in listOf(DOMAIN, target.sourceId, target.trackId,
                target.uri, target.evidenceToken)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_PART_BYTES) { "Owner receipt identity too large" }
            md.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            md.update(bytes)
        }
        return md.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
}
