package org.michimusic.core.metadata

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * ME-10W: bounded, owner-bound private progress evidence for a SEQUENTIAL batch.
 * It records neither audio nor user tag values. Its SHA detects accidental
 * corruption but is NOT a trust/authentication credential. It does not certify
 * any provider writes or let a batch bypass the single protected V1 journal.
 */
object MetadataBatchReceiptCodec {
    private const val HEADER="MICHI_METADATA_BATCH_RECEIPT_V1"
    private const val HEADER_V2="MICHI_METADATA_BATCH_RECEIPT_V2"
    private const val MAX_CHARS=24 * 1024
    private val uuid=Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val sha=Regex("[0-9a-f]{64}")
    data class Record(val batchId: String, val rows: List<Row>,
                      val reviewSha256: String? = null)
    data class Row(val index: Int, val ownerSha256: String,
                   val status: MetadataBatchRunPolicy.State, val transactionId: String?)
    enum class Reason { INVALID_BATCH, INVALID_ROW, OWNER_MISMATCH, CORRUPT_OR_TRUNCATED,
        DUPLICATE_TRANSACTION, RESOURCE_LIMIT }
    sealed interface Result {
        data class Valid(val record: Record) : Result
        data class Blocked(val reason: Reason) : Result
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2,'0')
        }
    private fun owner(snapshot: MetadataSnapshot): String =
        MetadataPrivateSessionIdentity.digest(snapshot.target)
    fun create(batchId: String, snapshots: List<MetadataSnapshot>,
               rows: List<MetadataBatchRunPolicy.FileResult>,
               reviews: List<MetadataReview>? = null): Result {
        if (!uuid.matches(batchId) || snapshots.size != rows.size ||
            MetadataEditPreview.reviewBatch(snapshots,emptyList())
                is MetadataEditPreview.BatchReview.Blocked)
            return Result.Blocked(Reason.INVALID_BATCH)
        val receipts=rows.mapIndexed { i, row ->
            if (row.index != i || row.target != snapshots[i].target)
                return Result.Blocked(Reason.OWNER_MISMATCH)
            if (row.transactionId != null && !uuid.matches(row.transactionId))
                return Result.Blocked(Reason.INVALID_ROW)
            val needsId=row.state !in setOf(MetadataBatchRunPolicy.State.UNCHANGED,
                MetadataBatchRunPolicy.State.NOT_STARTED,
                MetadataBatchRunPolicy.State.PREPARATION_BLOCKED)
            if (needsId != (row.transactionId != null))
                return Result.Blocked(Reason.INVALID_ROW)
            Row(i,owner(snapshots[i]),row.state,row.transactionId)
        }
        if (receipts.mapNotNull { it.transactionId }.distinct().size !=
            receipts.count { it.transactionId != null })
            return Result.Blocked(Reason.DUPLICATE_TRANSACTION)
        val reviewHash = if (reviews != null) {
            MetadataBatchReviewDigest.digest(snapshots,reviews)
                ?: return Result.Blocked(Reason.INVALID_BATCH)
        } else null
        return Result.Valid(Record(batchId,receipts,reviewHash))
    }
    fun encode(record: Record): String {
        require(uuid.matches(record.batchId) && record.rows.size in 1..128)
        require(record.rows.map { it.index } == record.rows.indices.toList())
        val body=buildString {
            append(if (record.reviewSha256 == null) HEADER else HEADER_V2)
                .append('|').append(record.batchId).append('|').append(record.rows.size)
            if (record.reviewSha256 != null) {
                require(sha.matches(record.reviewSha256))
                append('|').append(record.reviewSha256)
            }
            append('\n')
            record.rows.forEach { row ->
                require(sha.matches(row.ownerSha256) &&
                    (row.transactionId == null || uuid.matches(row.transactionId)))
                append(row.index).append('|').append(row.ownerSha256).append('|')
                    .append(row.status.name).append('|')
                    .append(row.transactionId ?: "-").append('\n')
            }
        }
        require(body.length < MAX_CHARS)
        return body + "SHA256|" + digest(body) + "\n"
    }
    fun decode(text: String, current: List<MetadataSnapshot>,
               reviews: List<MetadataReview>? = null): Result {
        if (text.length !in 100..MAX_CHARS || current.isEmpty() || current.size > 128)
            return Result.Blocked(Reason.RESOURCE_LIMIT)
        val marker=text.lastIndexOf("SHA256|")
        if (marker < 0 || !text.endsWith("\n"))
            return Result.Blocked(Reason.CORRUPT_OR_TRUNCATED)
        val body=text.substring(0,marker)
        val expected=text.substring(marker+7).removeSuffix("\n")
        if (!sha.matches(expected) || digest(body) != expected)
            return Result.Blocked(Reason.CORRUPT_OR_TRUNCATED)
        if (!body.endsWith("\n")) return Result.Blocked(Reason.CORRUPT_OR_TRUNCATED)
        val lines=body.removeSuffix("\n").split('\n')
        val head=lines.firstOrNull()?.split('|').orEmpty()
        if (head.size !in 3..4 || !uuid.matches(head[1]) ||
            head[2] != current.size.toString() || lines.size != current.size + 1)
            return Result.Blocked(Reason.INVALID_BATCH)
        val reviewHash = when (head[0]) {
            HEADER -> if (head.size == 3 && reviews == null) null
                else return Result.Blocked(Reason.INVALID_BATCH)
            HEADER_V2 -> {
                if (head.size != 4 || !sha.matches(head[3]))
                    return Result.Blocked(Reason.INVALID_BATCH)
                if (reviews != null &&
                    MetadataBatchReviewDigest.digest(current,reviews) != head[3])
                    return Result.Blocked(Reason.OWNER_MISMATCH)
                head[3]
            }
            else -> return Result.Blocked(Reason.INVALID_BATCH)
        }
        val seen=mutableSetOf<String>()
        val rows=mutableListOf<Row>()
        for (index in current.indices) {
            val parts=lines[index+1].split('|')
            if (parts.size != 4 || parts[0] != index.toString() ||
                !sha.matches(parts[1]) || parts[1] != owner(current[index]))
                return Result.Blocked(Reason.OWNER_MISMATCH)
            val status=MetadataBatchRunPolicy.State.entries.firstOrNull { it.name == parts[2] }
                ?: return Result.Blocked(Reason.INVALID_ROW)
            val tx=parts[3].takeUnless { it == "-" }
            val needsId=status !in setOf(MetadataBatchRunPolicy.State.UNCHANGED,
                MetadataBatchRunPolicy.State.NOT_STARTED,
                MetadataBatchRunPolicy.State.PREPARATION_BLOCKED)
            if (needsId != (tx != null) || tx != null && !uuid.matches(tx))
                return Result.Blocked(Reason.INVALID_ROW)
            if (tx != null && !seen.add(tx))
                return Result.Blocked(Reason.DUPLICATE_TRANSACTION)
            rows += Row(index,parts[1],status,tx)
        }
        return Result.Valid(Record(head[1],rows.toList(),reviewHash))
    }
}
