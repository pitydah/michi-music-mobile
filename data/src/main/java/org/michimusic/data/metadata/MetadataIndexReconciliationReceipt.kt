package org.michimusic.data.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy

/**
 * ME-13 S3/S4: durable, versioned obligation to publish a physically verified
 * source into Room. Kept beside Journal V1, never derived from UI state.
 *
 * V1 carries only the physical original/candidate fingerprints. It is retained
 * for conservative readback but CANNOT authorize Room CAS: it lacks the exact
 * principal token and the pre-publication row identity.
 *
 * V2 adds the owner evidence token used by the durable intent plus the exact
 * cached-row identity observed before the physical write. The receipt is still
 * NOT a CAS authorization by itself: the reconciler re-reads the journal, the
 * intent, both private artifacts and the provider source before publishing.
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
    /** V2 only: the opaque token bound into the private intent owner digest. */
    val ownerEvidenceToken: String = "",
    /** V2 only: cached_tracks.verifiedAt before the physical write. */
    val previousVerifiedAt: Long = 0L,
    /** V2 only: cached_tracks.verifiedSha256 before the physical write. */
    val previousVerifiedSha256: String = "",
    /** V2 only: cached_tracks.size before the physical write. */
    val previousSize: Long = 0L,
) {
    fun expectedIndex(): MetadataIndexPublicationPolicy.IndexIdentity? {
        if (!actionable) return null
        return MetadataIndexPublicationPolicy.IndexIdentity(
            sourceId = sourceId,
            trackId = trackId,
            uri = uri,
            version = previousVerifiedAt,
            observedSourceSha256 = previousVerifiedSha256,
        )
    }

    /**
     * A V2 receipt with an exact pre-write row identity is the only variant that
     * may reach the reconciler CAS. V1 remains readable and is never deleted.
     */
    val actionable: Boolean
        get() = version == VERSION &&
            sourceId == LOCAL_SOURCE &&
            uri.startsWith("content://media/") &&
            ownerEvidenceToken.isNotBlank() &&
            previousSize in 1..MAX_BYTES &&
            (previousVerifiedAt >= 0L) &&
            (previousVerifiedSha256.isEmpty() || HEX.matches(previousVerifiedSha256)) &&
            (previousVerifiedAt > 0L) == previousVerifiedSha256.isNotEmpty()

    fun encode(): ByteArray {
        require(version == 1 || version == VERSION) { "INVALID_RECEIPT_VERSION" }
        require(uri.startsWith("content://"))
        require(sourceId.isNotBlank() && trackId.isNotBlank())
        require(HEX.matches(originalSha256) && HEX.matches(candidateSha256))
        require(candidateBytes in 1..MAX_BYTES)
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC); out.writeInt(version)
            out.writeUTF(transactionId); out.writeUTF(sourceId); out.writeUTF(trackId)
            out.writeUTF(uri); out.writeUTF(originalSha256); out.writeUTF(candidateSha256)
            out.writeLong(candidateBytes); out.writeUTF(format)
            if (version == VERSION) {
                require(ownerEvidenceToken.isNotBlank() &&
                    ownerEvidenceToken.length <= MAX_TOKEN) { "INVALID_OWNER_TOKEN" }
                require(previousSize in 1..MAX_BYTES) { "INVALID_PREVIOUS_SIZE" }
                require(previousVerifiedAt >= 0L) { "INVALID_PREVIOUS_TIME" }
                require(previousVerifiedSha256.isEmpty() ||
                    HEX.matches(previousVerifiedSha256)) { "INVALID_PREVIOUS_DIGEST" }
                require((previousVerifiedAt > 0L) ==
                    previousVerifiedSha256.isNotEmpty()) { "INCOMPLETE_PREVIOUS_IDENTITY" }
                out.writeUTF(ownerEvidenceToken)
                out.writeLong(previousVerifiedAt)
                out.writeUTF(previousVerifiedSha256)
                out.writeLong(previousSize)
            }
        }
        val data = payload.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out -> out.writeInt(data.size); out.write(data); out.write(digest) }
        }.toByteArray()
    }

    companion object {
        const val VERSION = 2
        const val LOCAL_SOURCE = "lib_device_local"
        const val MAX_FILE_BYTES = 16 * 1024L
        const val MAX_RECEIPTS = 32
        private const val MAGIC = 0x4D453133
        private const val MAX_TOKEN = 8192
        private const val MAX_BYTES = 1024L * 1024L * 1024L
        private val HEX = Regex("[a-f0-9]{64}")
        private val NAME = Regex("index-reconciliation-[0-9a-f-]{36}\\.bin")
        private val TX = Regex("[0-9a-f-]{36}")

        fun decode(bytes: ByteArray): MetadataIndexReconciliationReceipt? = try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val length = input.readInt()
            require(length in 1..4096 && bytes.size == length + 36)
            val payload = ByteArray(length); input.readFully(payload)
            val digest = ByteArray(32); input.readFully(digest)
            require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(digest))
            val stream = DataInputStream(ByteArrayInputStream(payload))
            require(stream.readInt() == MAGIC)
            val version = stream.readInt()
            require(version == 1 || version == VERSION)
            val transactionId = stream.readUTF()
            val sourceId = stream.readUTF()
            val trackId = stream.readUTF()
            val uri = stream.readUTF()
            val originalSha256 = stream.readUTF()
            val candidateSha256 = stream.readUTF()
            val candidateBytes = stream.readLong()
            val format = stream.readUTF()
            val receipt = if (version == 1) {
                require(stream.available() == 0)
                MetadataIndexReconciliationReceipt(
                    transactionId = transactionId, sourceId = sourceId,
                    trackId = trackId, uri = uri,
                    originalSha256 = originalSha256, candidateSha256 = candidateSha256,
                    candidateBytes = candidateBytes, format = format, version = version,
                )
            } else {
                val ownerEvidenceToken = stream.readUTF()
                val previousVerifiedAt = stream.readLong()
                val previousVerifiedSha256 = stream.readUTF()
                val previousSize = stream.readLong()
                require(stream.available() == 0)
                MetadataIndexReconciliationReceipt(
                    transactionId = transactionId, sourceId = sourceId,
                    trackId = trackId, uri = uri,
                    originalSha256 = originalSha256, candidateSha256 = candidateSha256,
                    candidateBytes = candidateBytes, format = format, version = version,
                    ownerEvidenceToken = ownerEvidenceToken,
                    previousVerifiedAt = previousVerifiedAt,
                    previousVerifiedSha256 = previousVerifiedSha256,
                    previousSize = previousSize,
                )
            }
            require(receipt.uri.startsWith("content://"))
            require(HEX.matches(receipt.originalSha256) && HEX.matches(receipt.candidateSha256))
            receipt
        } catch (_: Exception) { null }

        fun file(root: File, transactionId: String): File {
            require(TX.matches(transactionId))
            return File(root, "index-reconciliation-$transactionId.bin")
        }

        /** Bounded, symlink-free enumeration of private receipts. */
        fun list(root: File): List<File> = try {
            if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) emptyList()
            else root.listFiles().orEmpty()
                .asSequence()
                .filter { NAME.matches(it.name) }
                .filter {
                    !Files.isSymbolicLink(it.toPath()) && it.isFile &&
                        it.length() in 1..MAX_FILE_BYTES
                }
                .sortedBy { it.name }
                .take(MAX_RECEIPTS)
                .toList()
        } catch (_: Exception) { emptyList() }

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

        fun read(file: File): MetadataIndexReconciliationReceipt? = try {
            if (!NAME.matches(file.name) || Files.isSymbolicLink(file.toPath()) ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.length() !in 1..MAX_FILE_BYTES) null
            else decode(Files.readAllBytes(file.toPath()))
        } catch (_: Exception) { null }
    }
}
