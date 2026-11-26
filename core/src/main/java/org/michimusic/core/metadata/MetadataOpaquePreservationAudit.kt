package org.michimusic.core.metadata

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale

/**
 * ME-10P: full, read-only PRIVATE backup/stage preservation audit for the
 * conservative ID3v2.3/2.4 and FLAC stage writers. All audio bytes and all
 * unedited opaque payloads are compared; changed-tag parsing is separately
 * enforced by MetadataCandidateSemanticPolicy.
 *
 * Nothing here reads source content:// or licenses a provider mutation. A
 * snapshot is trusted only while the same locked journal owns both private files.
 */
object MetadataOpaquePreservationAudit {
    enum class Refusal {
        WRONG_FORMAT, UNSUPPORTED_EDIT, UNSUPPORTED_FLAGS, BROKEN_CONTAINER,
        MISSING_COMMENT, DUPLICATE_COMMENT, RESOURCE_LIMIT, AUDIO_CHANGED,
        UNEDITED_OPAQUE_CHANGED, UNVERIFIABLE_PRIVATE_FILE,
    }
    sealed interface Verdict {
        /** Only proves a subset of the private bytes, not provider durability. */
        data class Compared(val opaqueRecords: Int, val audioBytes: Long) : Verdict {
            val canCommitToProvider: Boolean get() = false
        }
        data class Blocked(val reason: Refusal) : Verdict
    }
    private const val MAX_FILE = 1024L * 1024 * 1024
    private const val MAX_TAG = 16 * 1024 * 1024
    private const val MAX_META = 64L * 1024 * 1024
    private const val MAX_ITEMS = 8192
    private fun u(b: Byte) = b.toInt() and 0xff
    private fun be32(bytes: ByteArray, offset: Int): Long =
        (u(bytes[offset]).toLong() shl 24) or (u(bytes[offset + 1]).toLong() shl 16) or
            (u(bytes[offset + 2]).toLong() shl 8) or u(bytes[offset + 3]).toLong()
    private fun le32(bytes: ByteArray, offset: Int): Long =
        u(bytes[offset]).toLong() or (u(bytes[offset + 1]).toLong() shl 8) or
            (u(bytes[offset + 2]).toLong() shl 16) or (u(bytes[offset + 3]).toLong() shl 24)
    private fun synchsafe(bytes: ByteArray, offset: Int): Long {
        if ((0..3).any { u(bytes[offset + it]) >= 128 }) throw Stop(Refusal.BROKEN_CONTAINER)
        return (0..3).fold(0L) { a, i -> (a shl 7) or u(bytes[offset + i]).toLong() }
    }
    private class Stop(val why: Refusal) : Exception()
    private data class DigestRecord(val name: String, val sha: String)
    private data class Observation(val opaque: List<DigestRecord>, val audio: String, val audioBytes: Long)
    private data class ResourceExclusions(
        val beforePictures: Set<Int> = emptySet(),
        val afterPictures: Set<Int> = emptySet(),
        val described: Set<MetadataDescribedTextWorkbench.Identity> = emptySet(),
        val describedFields: Set<MetadataField> = emptySet(),
    )

    /**
     * A resource edit is addressed by an exact identity: ordinal for artwork,
     * kind/language/descriptor for COMM/USLT. Only those identities are
     * excluded from the opaque comparison; every unselected frame or block
     * remains digest-checked exactly as before.
     */
    private fun resourceExclusions(
        resources: List<MetadataCommitIntentManifest.ResourceChange>,
    ): ResourceExclusions {
        val before = linkedSetOf<Int>()
        val after = linkedSetOf<Int>()
        val described = linkedSetOf<MetadataDescribedTextWorkbench.Identity>()
        val describedFields = linkedSetOf<MetadataField>()
        for (resource in resources) when (resource) {
            is MetadataCommitIntentManifest.ResourceChange.PictureReplacement -> {
                before += resource.ordinal
                after += resource.ordinal
            }
            is MetadataCommitIntentManifest.ResourceChange.PictureAdd ->
                after += resource.ordinal
            is MetadataCommitIntentManifest.ResourceChange.PictureRemove ->
                before += resource.ordinal
            is MetadataCommitIntentManifest.ResourceChange.DescribedText -> {
                described += MetadataDescribedTextWorkbench.Identity(
                    resource.kind, resource.language, resource.description,
                )
                describedFields += resource.kind.field
            }
        }
        return ResourceExclusions(before, after, described, describedFields)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class Reader(val channel: FileChannel) {
        val length: Long = channel.size()
        fun take(offset: Long, count: Int): ByteArray {
            if (count !in 0..MAX_TAG || offset < 0 || offset > length ||
                count.toLong() > length - offset) throw Stop(Refusal.BROKEN_CONTAINER)
            val buffer = ByteBuffer.allocate(count)
            var at = offset
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, at)
                if (n <= 0) throw Stop(Refusal.BROKEN_CONTAINER)
                at += n
            }
            return buffer.array()
        }
        fun digest(start: Long): String {
            if (start !in 0..length) throw Stop(Refusal.BROKEN_CONTAINER)
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(64 * 1024)
            var position = start
            while (position < length) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), length - position).toInt())
                val n = channel.read(buffer, position)
                if (n <= 0) throw Stop(Refusal.BROKEN_CONTAINER)
                sha.update(buffer.array(), 0, n)
                position += n
            }
            return sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    private fun id3(reader: Reader, format: MetadataAudioContainer,
                    changes: Set<MetadataField>,
                    pictures: Set<Int>,
                    describedResources: Set<MetadataDescribedTextWorkbench.Identity>): Observation {
        if (reader.length < 10) throw Stop(Refusal.BROKEN_CONTAINER)
        val header = reader.take(0, 10)
        if (String(header, 0, 3, StandardCharsets.US_ASCII) != "ID3" ||
            u(header[3]) != if (format == MetadataAudioContainer.ID3_V23) 3 else 4)
            throw Stop(Refusal.WRONG_FORMAT)
        if (u(header[4]) != 0 || u(header[5]) != 0) throw Stop(Refusal.UNSUPPORTED_FLAGS)
        val tagLength = synchsafe(header, 6)
        if (tagLength > MAX_TAG) throw Stop(Refusal.RESOURCE_LIMIT)
        val end = 10L + tagLength
        if (end > reader.length) throw Stop(Refusal.BROKEN_CONTAINER)
        // Field identity, not the coarse four-character frame ID, determines
        // which entry is intentionally edited. TXXX descriptors and COMM/USLT
        // language/description identities are independently validated below.
        val version = if (format == MetadataAudioContainer.ID3_V23) 3 else 4
        val edited = changes.filterNot { it in MetadataMusicBrainzTagMap.id3Fields ||
            it in MetadataId3DescribedTagCodec.fields }.map {
            MetadataId3StageRewriter.preservationFrameId(it, version)
                ?: throw Stop(Refusal.UNSUPPORTED_EDIT)
        }.toSet()
        val editedTxxx = changes.intersect(MetadataMusicBrainzTagMap.id3Fields)
        val editedDescribed = changes.intersect(MetadataId3DescribedTagCodec.fields)
        // The stage writer rejects ID3v1; this audit also rejects a drift into it.
        if (reader.length >= 128 &&
            String(reader.take(reader.length - 128, 3), StandardCharsets.US_ASCII) == "TAG")
            throw Stop(Refusal.UNSUPPORTED_FLAGS)
        val tag = reader.take(10L, tagLength.toInt())
        val records = mutableListOf<DigestRecord>()
        var p = 0
        var apicOrdinal = 0
        while (p < tag.size) {
            if (tag.size - p < 10 || (0..3).all { tag[p + it] == 0.toByte() }) {
                if ((p until tag.size).any { tag[it] != 0.toByte() })
                    throw Stop(Refusal.BROKEN_CONTAINER)
                break // only zero padding is allowed and may change in length
            }
            if (records.size >= MAX_ITEMS) throw Stop(Refusal.RESOURCE_LIMIT)
            val name = String(tag, p, 4, StandardCharsets.US_ASCII)
            if (!name.matches(Regex("[A-Z0-9]{4}"))) throw Stop(Refusal.BROKEN_CONTAINER)
            val payloadSize = if (format == MetadataAudioContainer.ID3_V23) be32(tag, p + 4)
                else synchsafe(tag, p + 4)
            if (payloadSize == 0L || payloadSize > tag.size.toLong() - p - 10 ||
                tag[p + 8] != 0.toByte() || tag[p + 9] != 0.toByte())
                throw Stop(Refusal.UNSUPPORTED_FLAGS)
            val n = payloadSize.toInt()
            val pictureIndex = if (name == "APIC") apicOrdinal++ else -1
            val isEdited = when {
                pictureIndex >= 0 && pictureIndex in pictures -> true
                name in edited -> true
                name == "TXXX" && editedTxxx.isNotEmpty() -> {
                    val descriptor = MetadataMusicBrainzTagMap.decodeId3(
                        tag.copyOfRange(p + 10, p + 10 + n), version)
                        ?: throw Stop(Refusal.UNSUPPORTED_EDIT)
                    MetadataMusicBrainzTagMap.fromId3Description(descriptor.description) in editedTxxx
                }
                name == "COMM" || name == "USLT" -> {
                    val kind = MetadataId3DescribedTagCodec.Kind.entries
                        .firstOrNull { it.frameId == name }
                        ?: throw Stop(Refusal.UNSUPPORTED_EDIT)
                    val entry = MetadataId3DescribedTagCodec.decode(
                        kind, version, tag.copyOfRange(p + 10, p + 10 + n),
                    )
                    when {
                        entry != null && MetadataDescribedTextWorkbench.Identity(
                            kind, entry.language, entry.description,
                        ) in describedResources -> true
                        kind.field !in editedDescribed -> false
                        entry == null -> throw Stop(Refusal.UNSUPPORTED_EDIT)
                        !entry.canonical -> throw Stop(Refusal.UNSUPPORTED_EDIT)
                        else -> true
                    }
                }
                else -> false
            }
            if (!isEdited) records += DigestRecord(name, sha(tag.copyOfRange(p, p + 10 + n)))
            p += 10 + n
        }
        return Observation(records, reader.digest(end), reader.length - end)
    }

    private fun readComment(comment: ByteArray, exclude: Set<String>): List<DigestRecord> {
        var p = 0
        fun next(): ByteArray {
            if (p + 4 > comment.size) throw Stop(Refusal.BROKEN_CONTAINER)
            val length = le32(comment, p); p += 4
            if (length > comment.size.toLong() - p || length > MAX_TAG)
                throw Stop(Refusal.BROKEN_CONTAINER)
            val result = comment.copyOfRange(p, p + length.toInt())
            p += length.toInt()
            return result
        }
        val vendor = next()
        if (p + 4 > comment.size) throw Stop(Refusal.BROKEN_CONTAINER)
        val count = le32(comment, p); p += 4
        if (count > MAX_ITEMS) throw Stop(Refusal.RESOURCE_LIMIT)
        val result = mutableListOf(DigestRecord("VORBIS_VENDOR", sha(vendor)))
        repeat(count.toInt()) {
            val bytes = next()
            val separator = bytes.indexOf('='.code.toByte())
            if (separator <= 0 || separator > 128) throw Stop(Refusal.BROKEN_CONTAINER)
            val nameBytes = bytes.copyOfRange(0, separator)
            if (nameBytes.any { u(it) !in 0x20..0x7e })
                throw Stop(Refusal.BROKEN_CONTAINER)
            val name = String(nameBytes, StandardCharsets.US_ASCII).uppercase(Locale.ROOT)
            if (name !in exclude) result += DigestRecord("VORBIS:$name", sha(bytes))
        }
        if (p != comment.size) throw Stop(Refusal.BROKEN_CONTAINER)
        return result
    }

    private fun flac(reader: Reader, changes: Set<MetadataField>,
                     describedFields: Set<MetadataField>,
                     pictures: Set<Int>): Observation {
        if (reader.length < 8 ||
            String(reader.take(0, 4), StandardCharsets.US_ASCII) != "fLaC")
            throw Stop(Refusal.WRONG_FORMAT)
        val aliases = (changes + describedFields).flatMapTo(linkedSetOf()) { field ->
            val keys = MetadataFlacStageRewriter.preservationAliases(field)
            if (keys.isEmpty()) throw Stop(Refusal.UNSUPPORTED_EDIT)
            keys
        }
        var at = 4L
        var last = false
        var count = 0
        var pictureOrdinal = 0
        var commentSeen = false
        val records = mutableListOf<DigestRecord>()
        while (!last) {
            if (++count > 128 || at > MAX_META) throw Stop(Refusal.RESOURCE_LIMIT)
            val header = reader.take(at, 4)
            last = (u(header[0]) and 0x80) != 0
            val type = u(header[0]) and 0x7f
            val size = (u(header[1]) shl 16) or (u(header[2]) shl 8) or u(header[3])
            at += 4L
            if (type == 127 || size > MAX_TAG || at + size > reader.length ||
                at + size > MAX_META) throw Stop(Refusal.BROKEN_CONTAINER)
            when (type) {
                4 -> {
                    if (commentSeen) throw Stop(Refusal.DUPLICATE_COMMENT)
                    commentSeen = true
                    records += readComment(reader.take(at, size), aliases)
                }
                1 -> { // padding size may change; content may only be zero
                    val chunk = ByteBuffer.allocate(64 * 1024)
                    var pos = at
                    while (pos < at + size) {
                        chunk.clear()
                        chunk.limit(minOf((at + size - pos).toInt(), chunk.capacity()))
                        val n = reader.channel.read(chunk, pos)
                        if (n <= 0 || chunk.array().take(n).any { it != 0.toByte() })
                            throw Stop(Refusal.BROKEN_CONTAINER)
                        pos += n
                    }
                }
                6 -> {
                    val index = pictureOrdinal++
                    if (index !in pictures)
                        records += DigestRecord("FLAC_BLOCK:6", sha(reader.take(at, size)))
                }
                else -> records += DigestRecord("FLAC_BLOCK:$type", sha(reader.take(at, size)))
            }
            at += size
        }
        if (!commentSeen) throw Stop(Refusal.MISSING_COMMENT)
        return Observation(records, reader.digest(at), reader.length - at)
    }

    fun compare(backup: File, stage: File, container: MetadataAudioContainer,
                editedFields: Set<MetadataField>,
                resources: List<MetadataCommitIntentManifest.ResourceChange> = emptyList()): Verdict {
        val exclusions = resourceExclusions(resources)
        if (editedFields.isEmpty() && exclusions.beforePictures.isEmpty() &&
            exclusions.afterPictures.isEmpty() && exclusions.described.isEmpty())
            return Verdict.Blocked(Refusal.UNSUPPORTED_EDIT)
        if (container !in setOf(MetadataAudioContainer.FLAC,
                MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24))
            return Verdict.Blocked(Refusal.WRONG_FORMAT)
        try {
            if (!backup.isFile || !stage.isFile || backup == stage ||
                Files.isSymbolicLink(backup.toPath()) || Files.isSymbolicLink(stage.toPath()))
                return Verdict.Blocked(Refusal.UNVERIFIABLE_PRIVATE_FILE)
            FileChannel.open(backup.toPath(), StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS).use { a ->
                FileChannel.open(stage.toPath(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use { b ->
                    if (a.size() !in 12..MAX_FILE || b.size() !in 12..MAX_FILE)
                        throw Stop(Refusal.RESOURCE_LIMIT)
                    val x = Reader(a); val y = Reader(b)
                    val original = if (container == MetadataAudioContainer.FLAC)
                        flac(x, editedFields, exclusions.describedFields,
                            exclusions.beforePictures)
                    else id3(x, container, editedFields, exclusions.beforePictures,
                        exclusions.described)
                    val candidate = if (container == MetadataAudioContainer.FLAC)
                        flac(y, editedFields, exclusions.describedFields,
                            exclusions.afterPictures)
                    else id3(y, container, editedFields, exclusions.afterPictures,
                        exclusions.described)
                    if (original.audioBytes != candidate.audioBytes || original.audio != candidate.audio)
                        return Verdict.Blocked(Refusal.AUDIO_CHANGED)
                    if (original.opaque != candidate.opaque)
                        return Verdict.Blocked(Refusal.UNEDITED_OPAQUE_CHANGED)
                    return Verdict.Compared(original.opaque.size, original.audioBytes)
                }
            }
        } catch (stop: Stop) { return Verdict.Blocked(stop.why) }
          catch (_: Exception) { return Verdict.Blocked(Refusal.UNVERIFIABLE_PRIVATE_FILE) }
    }
}
