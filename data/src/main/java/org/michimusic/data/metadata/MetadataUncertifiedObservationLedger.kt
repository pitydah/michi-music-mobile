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

/**
 * ME-09. Small, durable, PRIVATE observation of a non-certified postcheck.
 * No URI, library ID, track metadata or raw bytes are written. This record is
 * explicitly NOT a SAVED receipt and can never authorize source writes or Room.
 * Call only after a user-requested operation/recovery inspection, under the
 * coordinator's existing serialization lock.
 */
internal class MetadataUncertifiedObservationLedger(private val root: File) {
    enum class Outcome {
        ORIGINAL_INTACT, CANDIDATE_UNCERTIFIED, UNKNOWN_BYTES,
        UNREADABLE, SEMANTIC_UNVERIFIED,
    }
    data class Record(
        val transactionId: String,
        val backupSha256: String,
        val candidateSha256: String,
        val outcome: Outcome,
        val sequence: Long,
    )
    sealed interface Read {
        data object Absent : Read
        data class Valid(val record: Record) : Read
        data object UntrustedKeepArtifacts : Read
    }

    private val id = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val sha = Regex("[a-f0-9]{64}")
    private val maxPayload = 2048
    private val file get() = File(root, "uncertified-observation-v1.bin")

    private fun valid(r: Record): Boolean = id.matches(r.transactionId) &&
        sha.matches(r.backupSha256) && sha.matches(r.candidateSha256) &&
        r.backupSha256 != r.candidateSha256 && r.sequence in 1..1_000_000_000L

    private fun safeRoot(): Boolean {
        val path = root.toPath()
        if (Files.isSymbolicLink(path)) return false
        Files.createDirectories(path)
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())
    }

    fun inspect(): Read = try {
        if (!safeRoot()) Read.UntrustedKeepArtifacts
        else if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Read.Absent
        else if (Files.size(file.toPath()) !in 37L..(maxPayload + 36).toLong())
            Read.UntrustedKeepArtifacts
        else {
            val bytes = Files.readAllBytes(file.toPath())
            if (bytes.size !in 37..(maxPayload + 36)) Read.UntrustedKeepArtifacts
            else decode(bytes)?.let(Read::Valid) ?: Read.UntrustedKeepArtifacts
        }
    } catch (_: Exception) { Read.UntrustedKeepArtifacts }

    /** Never replaces a record for another transaction or a corrupt ledger. */
    fun record(next: Record): Boolean {
        if (!valid(next)) return false
        val prev = inspect()
        if (prev == Read.UntrustedKeepArtifacts) return false
        if (prev is Read.Valid && (prev.record.transactionId != next.transactionId ||
                prev.record.backupSha256 != next.backupSha256 ||
                prev.record.candidateSha256 != next.candidateSha256 ||
                next.sequence != prev.record.sequence + 1)) return false
        if (prev == Read.Absent && next.sequence != 1L) return false
        val payload = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { out ->
                out.writeInt(0x4d453039); out.writeInt(1)
                out.writeUTF(next.transactionId)
                out.writeUTF(next.backupSha256)
                out.writeUTF(next.candidateSha256)
                out.writeUTF(next.outcome.name)
                out.writeLong(next.sequence)
            }
        }.toByteArray()
        if (payload.size > maxPayload) return false
        val framed = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { out ->
                out.writeInt(payload.size)
                out.write(payload)
                out.write(MessageDigest.getInstance("SHA-256").digest(payload))
            }
        }.toByteArray()
        val temp = File(root, "observation-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val bb = ByteBuffer.wrap(framed)
                while (bb.hasRemaining()) channel.write(bb)
                channel.force(true)
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            try { FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) } }
            catch (_: Exception) { /* no mutation / no success implied */ }
            return true
        } catch (_: Exception) { return false }
        finally { temp.delete() }
    }

    /** A corrupt record must never be deleted by an automatic recovery attempt. */
    fun clearIfKnown(original: Record, explicitConfirmation: Boolean): Boolean {
        if (!explicitConfirmation || inspect() != Read.Valid(original)) return false
        return try { Files.deleteIfExists(file.toPath()) } catch (_: Exception) { false }
    }

    private fun decode(bytes: ByteArray): Record? {
        return try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val size = input.readInt()
            if (size !in 1..maxPayload || bytes.size != size + 36) return null
            val payload = ByteArray(size)
            input.readFully(payload)
            val digest = ByteArray(32)
            input.readFully(digest)
            if (!MessageDigest.isEqual(digest,
                    MessageDigest.getInstance("SHA-256").digest(payload))) return null
            DataInputStream(ByteArrayInputStream(payload)).use { d ->
                if (d.readInt() != 0x4d453039 || d.readInt() != 1) return null
                val result=Record(d.readUTF(),d.readUTF(),d.readUTF(),
                    Outcome.valueOf(d.readUTF()),d.readLong())
                result.takeIf { d.available() == 0 && valid(it) }
            }
        }
        } catch (_: Exception) { null }
    }
}
