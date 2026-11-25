package org.michimusic.data.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataTarget

/**
 * ME-13 P0-A: durable owner evidence that does not depend on a Compose draft.
 *
 * The journal already binds the transaction, but recovery after a process kill
 * must be able to prove which opaque session owned the source without asking
 * the editor to reconstruct a review. The receipt is written before MUTATING,
 * is identity-bound to the exact transaction and source, and is deleted only by
 * the terminal cleanup. A missing or corrupt receipt is never reconstructed
 * from the currently open UI.
 */
internal data class MetadataRecoveryOwnerReceipt(
    val transactionId: String,
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val evidenceToken: String,
    val ownerSha256: String,
)

internal class MetadataRecoveryOwnerStore(private val root: File) {
    private companion object {
        const val MAGIC = 0x4d453036 // ME06
        const val VERSION = 1
        const val MAX_RECORD = 8 * 1024
        const val MAX_TOKEN = 256
        val id = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val sha = Regex("[a-f0-9]{64}")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
    }

    fun file(transactionId: String): File {
        require(id.matches(transactionId)) { "INVALID_PRIVATE_TRANSACTION_ID" }
        return File(root, "owner-$transactionId.bin")
    }

    private fun fsyncDirectory(): Boolean =
        MetadataDurableFile.syncDirectory(root) ==
            MetadataDurableFile.DirectorySyncResult.FORCED

    fun write(entry: MetadataRecoveryJournalV1.Entry, target: MetadataTarget): Boolean {
        if (entry.sourceId != target.sourceId || entry.trackId != target.trackId ||
            entry.uri != target.uri || target.evidenceToken.isBlank() ||
            target.evidenceToken.length > MAX_TOKEN ||
            target.evidenceToken.toByteArray(Charsets.UTF_8).any { it == 0.toByte() })
            return false
        val receipt = MetadataRecoveryOwnerReceipt(
            transactionId = entry.id,
            sourceId = entry.sourceId,
            trackId = entry.trackId,
            uri = entry.uri,
            evidenceToken = target.evidenceToken,
            ownerSha256 = MetadataPrivateSessionIdentity.digest(target),
        )
        val bytes = encode(receipt)
        val destination = file(entry.id)
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val existing = read(entry) ?: return false
            return existing == receipt
        }
        ensureDirectory()
        val temp = File(root, "owner-${UUID.randomUUID()}.tmp")
        return try {
            FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            if (!fsyncDirectory()) {
                runCatching { Files.deleteIfExists(destination.toPath()) }
                false
            } else true
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
        }
    }

    fun read(entry: MetadataRecoveryJournalV1.Entry): MetadataRecoveryOwnerReceipt? {
        val file = try { file(entry.id) } catch (_: Exception) { return null }
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(file.toPath()) || file.length() !in 1..MAX_RECORD.toLong())
            return null
        return try {
            val decoded = decode(Files.readAllBytes(file.toPath()))
            if (decoded.transactionId != entry.id || decoded.sourceId != entry.sourceId ||
                decoded.trackId != entry.trackId || decoded.uri != entry.uri ||
                !sha.matches(decoded.ownerSha256) || decoded.evidenceToken.isBlank())
                null
            else decoded
        } catch (_: Exception) { null }
    }

    fun delete(entry: MetadataRecoveryJournalV1.Entry): Boolean {
        val file = try { file(entry.id) } catch (_: Exception) { return false }
        if (Files.isSymbolicLink(file.toPath())) return false
        return !file.exists() || file.delete()
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(root.toPath())) error("OWNER_DIRECTORY_SYMLINK")
        Files.createDirectories(root.toPath())
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            error("OWNER_DIRECTORY_INVALID")
    }

    private fun encode(receipt: MetadataRecoveryOwnerReceipt): ByteArray {
        require(id.matches(receipt.transactionId)) { "INVALID_PRIVATE_TRANSACTION_ID" }
        require(receipt.evidenceToken.isNotBlank() &&
            receipt.evidenceToken.length <= MAX_TOKEN) { "INVALID_OWNER_TOKEN" }
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(receipt.transactionId)
            out.writeUTF(receipt.sourceId)
            out.writeUTF(receipt.trackId)
            out.writeUTF(receipt.uri)
            out.writeUTF(receipt.evidenceToken)
            out.writeUTF(receipt.ownerSha256)
        }
        val data = payload.toByteArray()
        require(data.size in 1..MAX_RECORD) { "OWNER_RECORD_TOO_BIG" }
        return ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(data.size)
                out.write(data)
                out.write(MessageDigest.getInstance("SHA-256").digest(data))
            }
        }.toByteArray()
    }

    private fun decode(raw: ByteArray): MetadataRecoveryOwnerReceipt {
        require(raw.size in 37..MAX_RECORD + 36)
        val stream = DataInputStream(ByteArrayInputStream(raw))
        val length = stream.readInt()
        require(length in 1..MAX_RECORD && raw.size == length + 36)
        val payload = ByteArray(length)
        stream.readFully(payload)
        val hash = ByteArray(32)
        stream.readFully(hash)
        require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(hash))
        val data = DataInputStream(ByteArrayInputStream(payload))
        require(data.readInt() == MAGIC && data.readInt() == VERSION)
        val receipt = MetadataRecoveryOwnerReceipt(
            transactionId = data.readUTF(),
            sourceId = data.readUTF(),
            trackId = data.readUTF(),
            uri = data.readUTF(),
            evidenceToken = data.readUTF(),
            ownerSha256 = data.readUTF(),
        )
        require(data.available() == 0)
        require(id.matches(receipt.transactionId))
        require(receipt.evidenceToken.isNotBlank() &&
            receipt.evidenceToken.length <= MAX_TOKEN)
        require(sha.matches(receipt.ownerSha256))
        return receipt
    }
}
