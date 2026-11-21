package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * ME-10Z: fingerprint of an exact, ordered batch review; not a write grant.
 * A resumed receipt must correspond to the same verified tracks, order and
 * before/after value lists. No delimiter-based serialization of user values.
 */
object MetadataBatchReviewDigest {
    private fun put(md: MessageDigest, value: String) {
        val raw=value.toByteArray(Charsets.UTF_8)
        md.update(ByteBuffer.allocate(4).putInt(raw.size).array())
        md.update(raw)
    }
    fun digest(snapshots: List<MetadataSnapshot>, reviews: List<MetadataReview>): String? {
        if (MetadataBatchSequentialPolicy.initial(reviews,snapshots) == null) return null
        val md=MessageDigest.getInstance("SHA-256")
        put(md,"MICHI_METADATA_BATCH_REVIEW_V2")
        md.update(ByteBuffer.allocate(4).putInt(snapshots.size).array())
        for (index in snapshots.indices) {
            val review=reviews[index]
            put(md,index.toString())
            put(md,MetadataPrivateSessionIdentity.digest(snapshots[index].target))
            when(review) {
                MetadataReview.Unchanged -> put(md,"UNCHANGED")
                is MetadataReview.Ready -> {
                    put(md,"READY")
                    val changes=review.diffs.sortedBy { it.field.name }
                    put(md,changes.size.toString())
                    for (change in changes) {
                        put(md,change.field.name)
                        put(md,MetadataCommitIntentManifest.valuesDigest(change.before))
                        put(md,MetadataCommitIntentManifest.valuesDigest(change.after))
                    }
                }
                is MetadataReview.Blocked -> return null
            }
        }
        return md.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    }
}
