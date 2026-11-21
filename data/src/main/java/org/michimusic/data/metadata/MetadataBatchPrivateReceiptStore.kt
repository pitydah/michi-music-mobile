package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataBatchReceiptCodec
import org.michimusic.core.metadata.MetadataBatchRunPolicy
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataReview

/**
 * ME-10W: app-private, noBackup, opt-in progress *observations* per ordered
 * batch. It does not own, mutate or replace the existing single-transaction
 * source recovery journal. It cannot trigger a new preparation on restart.
 * Atomic replace requires the filesystem to support ATOMIC_MOVE; otherwise
 * previous receipts remain and operation refuses. Not multiprocess-certified.
 */
internal class MetadataBatchPrivateReceiptStore(context: Context) {
    private val root=File(context.noBackupFilesDir,"michi-metadata-batches-v1")
    private val mutex=Mutex()
    private val name=Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    sealed interface Read {
        data object NotFound : Read
        data class Verified(val record: MetadataBatchReceiptCodec.Record) : Read
        data object UntrustedKeepExisting : Read
    }
    enum class StoreResult { STORED, REFUSED_OR_UNVERIFIABLE }

    suspend fun load(batchId: String, current: List<MetadataSnapshot>,
        reviews: List<MetadataReview>? = null): Read =
        withContext(Dispatchers.IO) { mutex.withLock { loadLocked(batchId,current,reviews) } }

    suspend fun save(batchId: String, current: List<MetadataSnapshot>,
        rows: List<MetadataBatchRunPolicy.FileResult>,
        reviews: List<MetadataReview>? = null): StoreResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val created=MetadataBatchReceiptCodec.create(batchId,current,rows,reviews)
                        as? MetadataBatchReceiptCodec.Result.Valid
                        ?: return@withLock StoreResult.REFUSED_OR_UNVERIFIABLE
                    if (!root.exists() && !root.mkdirs())
                        return@withLock StoreResult.REFUSED_OR_UNVERIFIABLE
                    if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory)
                        return@withLock StoreResult.REFUSED_OR_UNVERIFIABLE
                    val previous=loadLocked(batchId,current,reviews)
                    if (previous == Read.UntrustedKeepExisting)
                        return@withLock StoreResult.REFUSED_OR_UNVERIFIABLE
                    if (previous is Read.Verified &&
                        !forwardOnly(previous.record,created.record))
                        return@withLock StoreResult.REFUSED_OR_UNVERIFIABLE
                    val content=MetadataBatchReceiptCodec.encode(created.record)
                        .toByteArray(Charsets.UTF_8)
                    if (content.size > 24*1024)
                        return@withLock StoreResult.REFUSED_OR_UNVERIFIABLE
                    val temp=Files.createTempFile(root.toPath(),"write-",".tmp")
                    try {
                        FileChannel.open(temp,StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS).use { channel ->
                            val buf=ByteBuffer.wrap(content)
                            while(buf.hasRemaining()) if (channel.write(buf) <= 0)
                                error("BATCH_RECEIPT_SHORT_WRITE")
                            channel.force(true)
                        }
                        Files.move(temp,receiptFile(batchId).toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING)
                        StoreResult.STORED
                    } finally { Files.deleteIfExists(temp) }
                } catch (cancel: CancellationException) { throw cancel }
                  catch (_: Exception) { StoreResult.REFUSED_OR_UNVERIFIABLE }
            }
        }

    private fun receiptFile(id: String): File {
        require(name.matches(id))
        return File(root,"batch-$id.receipt")
    }
    private fun loadLocked(batchId: String,current: List<MetadataSnapshot>,
        reviews: List<MetadataReview>?): Read {
        return try {
            if (!name.matches(batchId) || Files.isSymbolicLink(root.toPath()))
                return Read.UntrustedKeepExisting
            val file=receiptFile(batchId)
            if (!file.exists()) return Read.NotFound
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) ||
                Files.size(file.toPath()) !in 100..(24L*1024L))
                return Read.UntrustedKeepExisting
            val bytes=Files.readAllBytes(file.toPath())
            val text=Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            when(val parsed=MetadataBatchReceiptCodec.decode(text,current,reviews)) {
                is MetadataBatchReceiptCodec.Result.Valid -> Read.Verified(parsed.record)
                is MetadataBatchReceiptCodec.Result.Blocked -> Read.UntrustedKeepExisting
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Read.UntrustedKeepExisting }
    }
    private fun forwardOnly(old: MetadataBatchReceiptCodec.Record,
        next: MetadataBatchReceiptCodec.Record): Boolean {
        if (old.batchId != next.batchId || old.rows.size != next.rows.size ||
            old.reviewSha256 != next.reviewSha256) return false
        return old.rows.zip(next.rows).all { (a,b) ->
            if (a.index != b.index || a.ownerSha256 != b.ownerSha256) return@all false
            if (a.transactionId != null && a.transactionId != b.transactionId) return@all false
            if (a.status == b.status) return@all true
            when(a.status) {
                MetadataBatchRunPolicy.State.NOT_STARTED -> b.status in setOf(
                    MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY,
                    MetadataBatchRunPolicy.State.PREPARATION_BLOCKED)
                MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY -> b.status in setOf(
                    MetadataBatchRunPolicy.State.PRIVATE_STAGE_DISCARDED,
                    MetadataBatchRunPolicy.State.SOURCE_MUTATION_UNKNOWN)
                MetadataBatchRunPolicy.State.SOURCE_MUTATION_UNKNOWN -> b.status in setOf(
                    MetadataBatchRunPolicy.State.SOURCE_CANDIDATE_OBSERVED_UNCERTIFIED,
                    MetadataBatchRunPolicy.State.ORIGINAL_OBSERVED_INTACT,
                    MetadataBatchRunPolicy.State.RECOVERY_REQUIRED,
                    MetadataBatchRunPolicy.State.RESTORED_OBSERVED)
                MetadataBatchRunPolicy.State.RECOVERY_REQUIRED -> b.status in setOf(
                    MetadataBatchRunPolicy.State.ORIGINAL_OBSERVED_INTACT,
                    MetadataBatchRunPolicy.State.RESTORED_OBSERVED)
                else -> false
            }
        }
    }
}
