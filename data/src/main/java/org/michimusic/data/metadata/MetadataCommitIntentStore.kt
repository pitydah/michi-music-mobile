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
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataArtworkEvidence
import org.michimusic.core.metadata.MetadataId3DescribedTagCodec

/**
 * ME-10N. Private, per-transaction manifest: no raw tag strings, no source writes.
 * All calls must be made inside MetadataRecoveryJournalV1.exclusive. Journal V1
 * transactions prepared before this record existed remain UNBOUND and NO-GO.
 */
internal class MetadataCommitIntentStore(private val journal: MetadataRecoveryJournalV1) {
    sealed interface Inspection {
        data class Present(val receipt: MetadataCommitIntentManifest.Receipt) : Inspection
        data object Missing : Inspection
        data object CorruptOrUntrusted : Inspection
    }
    private companion object {
        const val MAGIC = 0x4d45314e // ME1N
        const val VERSION = 3
        const val MAX_BYTES = 12 * 1024
    }

    fun write(entry: MetadataRecoveryJournalV1.Entry,
        receipt: MetadataCommitIntentManifest.Receipt) {
        require(entry.id == receipt.transactionId &&
            MetadataCommitIntentManifest.valid(receipt)) { "INTENT_NOT_BOUND_TO_JOURNAL" }
        val file = journal.intent(entry)
        check(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "INTENT_ALREADY_EXISTS" }
        val data = encode(receipt)
        val parent = requireNotNull(file.parentFile) { "INTENT_DIRECTORY_UNSAFE" }.toPath()
        check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(parent)) { "INTENT_DIRECTORY_UNSAFE" }
        val tmp = File(file.parentFile, "intent-${entry.id}-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buf = ByteBuffer.wrap(data)
                while (buf.hasRemaining()) channel.write(buf)
                channel.force(true)
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            if (MetadataDurableFile.syncDirectory(file.parentFile) !=
                MetadataDurableFile.DirectorySyncResult.FORCED) {
                runCatching { Files.deleteIfExists(file.toPath()) }
                throw IllegalStateException("INTENT_NOT_DURABLE")
            }
        } finally { tmp.delete() }
    }

    fun inspect(entry: MetadataRecoveryJournalV1.Entry): Inspection {
        val file = journal.intent(entry)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return Inspection.Missing
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            return Inspection.CorruptOrUntrusted
        return try {
            val length = Files.size(file.toPath())
            if (length !in 4L..MAX_BYTES.toLong()) return Inspection.CorruptOrUntrusted
            val bytes = FileChannel.open(file.toPath(), StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size != length) error("INTENT_CHANGED")
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) if (channel.read(buffer) <= 0)
                    error("INTENT_SHORT_READ")
                buffer.array()
            }
            val decoded = decode(bytes)
            if (decoded.transactionId != entry.id) Inspection.CorruptOrUntrusted
            else Inspection.Present(decoded)
        } catch (_: Exception) { Inspection.CorruptOrUntrusted }
    }

    private fun encode(r: MetadataCommitIntentManifest.Receipt): ByteArray {
        val body = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(MAGIC);out.writeInt(VERSION)
                out.writeUTF(r.transactionId);out.writeUTF(r.ownerSha256)
                out.writeUTF(r.provider.name);out.writeUTF(r.container.name)
                out.writeLong(r.originalBytes);out.writeUTF(r.originalSha256)
                out.writeLong(r.candidateBytes);out.writeUTF(r.candidateSha256)
                out.writeInt(r.changes.size)
                for (c in r.changes) {
                    out.writeUTF(c.field.name);out.writeInt(c.beforeCount);out.writeInt(c.afterCount)
                    out.writeUTF(c.beforeSha256);out.writeUTF(c.afterSha256)
                }
                out.writeInt(r.resources.size)
                for (resource in r.resources) when (resource) {
                    is MetadataCommitIntentManifest.ResourceChange.PictureReplacement -> {
                        out.writeByte(1)
                        out.writeInt(resource.ordinal)
                        out.writeUTF(resource.format.name)
                        out.writeInt(resource.type)
                        out.writeUTF(resource.description)
                        out.writeUTF(resource.beforeMime)
                        out.writeInt(resource.beforeBytes)
                        out.writeUTF(resource.beforeSha256)
                        out.writeUTF(resource.afterMime)
                        out.writeInt(resource.afterBytes)
                        out.writeUTF(resource.afterSha256)
                    }
                    is MetadataCommitIntentManifest.ResourceChange.DescribedText -> {
                        out.writeByte(2)
                        out.writeUTF(resource.kind.name)
                        out.writeUTF(resource.language)
                        out.writeUTF(resource.description)
                        out.writeInt(resource.beforeCount)
                        out.writeInt(resource.afterCount)
                        out.writeUTF(resource.beforeSha256)
                        out.writeUTF(resource.afterSha256)
                    }
                    is MetadataCommitIntentManifest.ResourceChange.PictureAdd -> {
                        out.writeByte(3)
                        out.writeInt(resource.ordinal)
                        out.writeUTF(resource.format.name)
                        out.writeInt(resource.type)
                        out.writeUTF(resource.description)
                        out.writeUTF(resource.afterMime)
                        out.writeInt(resource.afterBytes)
                        out.writeUTF(resource.afterSha256)
                    }
                    is MetadataCommitIntentManifest.ResourceChange.PictureRemove -> {
                        out.writeByte(4)
                        out.writeInt(resource.ordinal)
                        out.writeUTF(resource.format.name)
                        out.writeInt(resource.type)
                        out.writeUTF(resource.description)
                        out.writeUTF(resource.beforeMime)
                        out.writeInt(resource.beforeBytes)
                        out.writeUTF(resource.beforeSha256)
                    }
                }
            }
        }.toByteArray()
        check(body.size + 4 + 32 <= MAX_BYTES) { "INTENT_RECORD_TOO_LARGE" }
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        return ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(body.size);out.write(body);out.write(digest)
            }
        }.toByteArray()
    }

    private fun decode(bytes: ByteArray): MetadataCommitIntentManifest.Receipt {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val bodyLength = input.readInt()
        require(bodyLength in 1..MAX_BYTES && bodyLength + 36 == bytes.size)
        val body = ByteArray(bodyLength)
        input.readFully(body)
        val expectedDigest = ByteArray(32)
        input.readFully(expectedDigest)
        require(input.available() == 0)
        require(MessageDigest.isEqual(expectedDigest, MessageDigest.getInstance("SHA-256").digest(body)))
        val decoded = DataInputStream(ByteArrayInputStream(body))
        require(decoded.readInt() == MAGIC)
        val version = decoded.readInt()
        require(version in 1..VERSION)
        val id = decoded.readUTF()
        val owner = decoded.readUTF()
        val provider = MetadataDocumentProvider.valueOf(decoded.readUTF())
        val container = MetadataAudioContainer.valueOf(decoded.readUTF())
        val originalBytes = decoded.readLong()
        val originalHash = decoded.readUTF()
        val candidateBytes = decoded.readLong()
        val candidateHash = decoded.readUTF()
        val count = decoded.readInt()
        require(count in if (version == 1) 1..64 else 0..64)
        val changes = List(count) {
            MetadataCommitIntentManifest.Change(MetadataField.valueOf(decoded.readUTF()),
                decoded.readInt(),decoded.readInt(),decoded.readUTF(),decoded.readUTF())
        }
        val resources = if (version == 1) emptyList() else {
            val resourceCount = decoded.readInt()
            require(resourceCount in 0..4)
            List(resourceCount) {
                when (decoded.readUnsignedByte()) {
                    1 -> MetadataCommitIntentManifest.ResourceChange.PictureReplacement(
                        ordinal = decoded.readInt(),
                        format = MetadataArtworkEvidence.Format.valueOf(decoded.readUTF()),
                        type = decoded.readInt(),
                        description = decoded.readUTF(),
                        beforeMime = decoded.readUTF(),
                        beforeBytes = decoded.readInt(),
                        beforeSha256 = decoded.readUTF(),
                        afterMime = decoded.readUTF(),
                        afterBytes = decoded.readInt(),
                        afterSha256 = decoded.readUTF(),
                    )
                    2 -> MetadataCommitIntentManifest.ResourceChange.DescribedText(
                        kind = MetadataId3DescribedTagCodec.Kind.valueOf(decoded.readUTF()),
                        language = decoded.readUTF(),
                        description = decoded.readUTF(),
                        beforeCount = decoded.readInt(),
                        afterCount = decoded.readInt(),
                        beforeSha256 = decoded.readUTF(),
                        afterSha256 = decoded.readUTF(),
                    )
                    3 -> {
                        require(version >= 3)
                        MetadataCommitIntentManifest.ResourceChange.PictureAdd(
                            ordinal = decoded.readInt(),
                            format = MetadataArtworkEvidence.Format.valueOf(decoded.readUTF()),
                            type = decoded.readInt(),
                            description = decoded.readUTF(),
                            afterMime = decoded.readUTF(),
                            afterBytes = decoded.readInt(),
                            afterSha256 = decoded.readUTF(),
                        )
                    }
                    4 -> {
                        require(version >= 3)
                        MetadataCommitIntentManifest.ResourceChange.PictureRemove(
                            ordinal = decoded.readInt(),
                            format = MetadataArtworkEvidence.Format.valueOf(decoded.readUTF()),
                            type = decoded.readInt(),
                            description = decoded.readUTF(),
                            beforeMime = decoded.readUTF(),
                            beforeBytes = decoded.readInt(),
                            beforeSha256 = decoded.readUTF(),
                        )
                    }
                    else -> error("INTENT_RESOURCE_KIND")
                }
            }
        }
        require(changes.isNotEmpty() || resources.isNotEmpty())
        require(decoded.available() == 0)
        val result = MetadataCommitIntentManifest.Receipt(id,owner,provider,container,
            originalBytes,originalHash,candidateBytes,candidateHash,changes,resources)
        require(MetadataCommitIntentManifest.valid(result))
        return result
    }
}
