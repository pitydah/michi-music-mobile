package org.michimusic.data.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * ME-13 S3: durable, versioned obligation to publish a physically verified
 * source into Room. Kept beside Journal V1, never derived from UI state.
 */
internal data class MetadataIndexReconciliationReceipt(
    val transactionId: String,
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val originalSha256: String,
    val candidateSha256: String,
    val candidateBytes: Long,
    val format: String,
    val version: Int = VERSION,
) {
    fun encode(): ByteArray {
        require(version == VERSION)
        require(uri.startsWith("content://"))
        require(sourceId.isNotBlank() && trackId.isNotBlank())
        require(HEX.matches(originalSha256) && HEX.matches(candidateSha256))
        require(candidateBytes > 0)
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC); out.writeInt(version)
            out.writeUTF(transactionId); out.writeUTF(sourceId); out.writeUTF(trackId)
            out.writeUTF(uri); out.writeUTF(originalSha256); out.writeUTF(candidateSha256)
            out.writeLong(candidateBytes); out.writeUTF(format)
        }
        val data = payload.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out -> out.writeInt(data.size); out.write(data); out.write(digest) }
        }.toByteArray()
    }

    companion object {
        const val VERSION = 1
        private const val MAGIC = 0x4D453133
        private val HEX = Regex("[a-f0-9]{64}")

        fun decode(bytes: ByteArray): MetadataIndexReconciliationReceipt? = try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val length = input.readInt()
            require(length in 1..4096 && bytes.size == length + 36)
            val payload = ByteArray(length); input.readFully(payload)
            val digest = ByteArray(32); input.readFully(digest)
            require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(digest))
            val stream = DataInputStream(ByteArrayInputStream(payload))
            require(stream.readInt() == MAGIC)
            val version = stream.readInt(); require(version == VERSION)
            val receipt = MetadataIndexReconciliationReceipt(
                transactionId = stream.readUTF(), sourceId = stream.readUTF(),
                trackId = stream.readUTF(), uri = stream.readUTF(),
                originalSha256 = stream.readUTF(), candidateSha256 = stream.readUTF(),
                candidateBytes = stream.readLong(), format = stream.readUTF(), version = version,
            )
            require(receipt.uri.startsWith("content://"))
            require(HEX.matches(receipt.originalSha256) && HEX.matches(receipt.candidateSha256))
            receipt
        } catch (_: Exception) { null }

        fun file(root: File, transactionId: String): File {
            require(Regex("[0-9a-f-]{36}").matches(transactionId))
            return File(root, "index-reconciliation-$transactionId.bin")
        }

        fun write(root: File, receipt: MetadataIndexReconciliationReceipt): Boolean = try {
            if (!root.exists()) root.mkdirs()
            val target = file(root, receipt.transactionId)
            if (Files.isSymbolicLink(target.toPath())) false else {
                val tmp = File(root, "index-reconciliation-${UUID.randomUUID()}.tmp")
                FileOutputStream(tmp).use { out -> out.write(receipt.encode()); out.fd.sync() }
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
                true
            }
        } catch (_: Exception) { false }

        fun read(root: File, transactionId: String): MetadataIndexReconciliationReceipt? =
            try { decode(Files.readAllBytes(file(root, transactionId).toPath())) }
            catch (_: Exception) { null }
    }
}
