package org.michimusic.data.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * ME-13 C3: durable terminal proof that the exact cached row was published
 * after a certified source write. It is written BEFORE the journal and private
 * artifacts are discarded, so a kill after the CAS can never resurrect a second
 * publication. The proof is removed last; a leftover proof is harmless and is
 * collected together with its reconciliation receipt.
 */
internal data class MetadataIndexAppliedReceipt(
    val transactionId: String,
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val candidateSha256: String,
    val candidateBytes: Long,
    val version: Int = VERSION,
) {
    fun encode(): ByteArray {
        require(TX.matches(transactionId) && sourceId.isNotBlank() && trackId.isNotBlank())
        require(validContentUri(uri))
        require(SHA.matches(candidateSha256) && candidateBytes in 1..MAX_BYTES)
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeUTF(transactionId); out.writeUTF(sourceId); out.writeUTF(trackId)
            out.writeUTF(uri); out.writeUTF(candidateSha256); out.writeLong(candidateBytes)
        }
        val data = payload.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(data.size); out.write(data); out.write(digest)
            }
        }.toByteArray()
    }

    companion object {
        const val VERSION = 1
        const val MAX_FILE_BYTES = 8 * 1024L
        private const val MAGIC = 0x4D453135
        private const val MAX_BYTES = 1024L * 1024L * 1024L
        private val SHA = Regex("[a-f0-9]{64}")
        private val TX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val NAME = Regex("index-applied-[0-9a-f-]{36}\\.bin")

        /**
         * This sidecar is only resumable cleanup proof. Provider authority is
         * established separately by the exact Room marker and typed permit.
         */
        private fun validContentUri(value: String): Boolean =
            runCatching { java.net.URI(value) }.getOrNull()?.let {
                it.scheme == "content" && !it.authority.isNullOrBlank()
            } == true

        fun decode(bytes: ByteArray): MetadataIndexAppliedReceipt? = try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val length = input.readInt()
            require(length in 1..4096 && bytes.size == length + 36)
            val payload = ByteArray(length); input.readFully(payload)
            val digest = ByteArray(32); input.readFully(digest)
            require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(digest))
            val stream = DataInputStream(ByteArrayInputStream(payload))
            require(stream.readInt() == MAGIC)
            require(stream.readInt() == VERSION)
            val receipt = MetadataIndexAppliedReceipt(
                transactionId = stream.readUTF(), sourceId = stream.readUTF(),
                trackId = stream.readUTF(), uri = stream.readUTF(),
                candidateSha256 = stream.readUTF(), candidateBytes = stream.readLong(),
            )
            require(stream.available() == 0)
            require(TX.matches(receipt.transactionId))
            require(validContentUri(receipt.uri))
            require(SHA.matches(receipt.candidateSha256))
            require(receipt.candidateBytes in 1..MAX_BYTES)
            receipt
        } catch (_: Exception) { null }

        fun file(root: File, transactionId: String): File {
            require(TX.matches(transactionId))
            return File(root, "index-applied-$transactionId.bin")
        }

        fun read(root: File, transactionId: String): MetadataIndexAppliedReceipt? = try {
            val file = file(root, transactionId)
            if (Files.isSymbolicLink(file.toPath()) ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.length() !in 1..MAX_FILE_BYTES) null
            else decode(Files.readAllBytes(file.toPath()))
        } catch (_: Exception) { null }

        /**
         * Atomic rename only. A durable terminal proof must never be reported as
         * written when the filesystem cannot replace it atomically.
         */
        fun write(root: File, receipt: MetadataIndexAppliedReceipt): Boolean = try {
            if (!root.exists()) root.mkdirs()
            val target = file(root, receipt.transactionId)
            if (Files.isSymbolicLink(target.toPath())) false else {
                val tmp = File(root, "index-applied-${UUID.randomUUID()}.tmp")
                try {
                    FileOutputStream(tmp).use { out ->
                        out.write(receipt.encode()); out.fd.sync()
                    }
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    if (Files.exists(tmp.toPath(), LinkOption.NOFOLLOW_LINKS)) tmp.delete()
                }
                if (MetadataDurableFile.syncDirectory(root) !=
                    MetadataDurableFile.DirectorySyncResult.FORCED) {
                    runCatching { Files.deleteIfExists(target.toPath()) }
                    false
                } else {
                    Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(target.toPath())
                }
            }
        } catch (_: Exception) { false }

        fun delete(root: File, transactionId: String): Boolean = try {
            val target = file(root, transactionId)
            !Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                (!Files.isSymbolicLink(target.toPath()) && target.delete())
        } catch (_: Exception) { false }
    }
}
