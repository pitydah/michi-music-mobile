package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

/**
 * ME-03: FLAC Vorbis-comment staging ONLY. All untouched metadata blocks (including
 * pictures, applications and padding) and the FLAC audio payload are copied without
 * interpreting them. Creates a NEW independent cache file; never writes a provider,
 * Room, Android URI, original source or a recovery journal. Caller MUST verify the
 * original-provider identity again and pass the Android crash-recovery gate before
 * any independent writer is ever permitted to commit. NOT CERTIFIED.
 */
object MetadataFlacStageRewriter {
    class Rejected(val code: Code) : IOException(code.name)
    enum class Code {
        NOT_A_READY_REVIEW, FIELD_UNSUPPORTED, INPUT_NOT_VERIFIED, SOURCE_NOT_SAFE,
        SOURCE_CHANGED, STAGING_ALREADY_EXISTS, INVALID_FLAC, INVALID_BLOCKS,
        DUPLICATE_COMMENT_BLOCK, MISSING_COMMENT_BLOCK, INVALID_COMMENT,
        AMBIGUOUS_OR_STALE_TAGS, TOO_MANY_COMMENTS, STAGED_TAG_TOO_LARGE,
        INVALID_UTF8, INSUFFICIENT_STAGING_SPACE,
    }
    data class Result(
        val format: MetadataAudioContainer = MetadataAudioContainer.FLAC,
        val fieldsChanged: Set<MetadataField>,
        val originalBytes: Long,
        val stagedBytes: Long,
        /** Copying the audio payload preserves bytes; a provider-level digest is still mandatory. */
        val audioBytesCopied: Long,
        val canCommitToProvider: Boolean = false,
    )

    private const val MAX_SOURCE = 1024L * 1024 * 1024
    private const val MAX_BLOCK = 16 * 1024 * 1024
    private const val MAX_TOTAL_METADATA = 64 * 1024 * 1024
    private const val MAX_BLOCKS = 128
    private const val MAX_COMMENTS = 4096
    private val fieldKeys = linkedMapOf(
        MetadataField.TITLE to setOf("TITLE"),
        MetadataField.ARTIST to setOf("ARTIST"),
        MetadataField.ALBUM to setOf("ALBUM"),
        MetadataField.ALBUM_ARTIST to setOf("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST"),
        MetadataField.GENRE to setOf("GENRE"),
        MetadataField.COMPOSER to setOf("COMPOSER"),
        MetadataField.RELEASE_DATE to setOf("DATE", "YEAR"),
        MetadataField.TRACK_NUMBER to setOf("TRACKNUMBER", "TRACK"),
        MetadataField.TRACK_TOTAL to setOf("TRACKTOTAL", "TOTALTRACKS"),
        MetadataField.DISC_NUMBER to setOf("DISCNUMBER", "DISC"),
        MetadataField.DISC_TOTAL to setOf("DISCTOTAL", "TOTALDISCS"),
        MetadataField.LABEL to setOf("LABEL", "ORGANIZATION"),
        MetadataField.CATALOG_NUMBER to setOf("CATALOGNUMBER"),
        MetadataField.ISRC to setOf("ISRC"),
        MetadataField.BPM to setOf("BPM"),
        MetadataField.MUSICAL_KEY to setOf("INITIALKEY"),
        MetadataField.ALBUM_SUBTITLE to setOf("ALBUMSUBTITLE"),
        MetadataField.ORIGINAL_ARTIST to setOf("ORIGINALARTIST"),
        MetadataField.ORCHESTRA to setOf("ORCHESTRA"),
        MetadataField.ENSEMBLE to setOf("ENSEMBLE"),
        MetadataField.SOLOIST to setOf("SOLOIST"),
        MetadataField.INSTRUMENT to setOf("INSTRUMENT"),
        MetadataField.MASTERING_ENGINEER to setOf("MASTERINGENGINEER"),
        MetadataField.EXECUTIVE_PRODUCER to setOf("EXECUTIVEPRODUCER"),
        MetadataField.WORK_NUMBER to setOf("WORKNUMBER"),
        MetadataField.OPUS_NUMBER to setOf("OPUS"),
        MetadataField.MOVEMENT_NUMBER to setOf("MOVEMENTNUMBER"),
        MetadataField.MOVEMENT_TOTAL to setOf("MOVEMENTTOTAL"),
        MetadataField.RECORDING_DATE to setOf("RECORDINGDATE"),
        MetadataField.EDITION to setOf("EDITION"),
        MetadataField.RELEASE_TYPE to setOf("RELEASETYPE"),
        MetadataField.RELEASE_STATUS to setOf("RELEASESTATUS"),
        MetadataField.MEDIA_FORMAT to setOf("MEDIA"),
        MetadataField.COMPILATION to setOf("COMPILATION"),
        MetadataField.SCRIPT to setOf("SCRIPT"),
        MetadataField.WORK to setOf("WORK"),
        MetadataField.MOVEMENT to setOf("MOVEMENT"),
        MetadataField.ARRANGER to setOf("ARRANGER"),
        MetadataField.LYRICIST to setOf("LYRICIST"),
        MetadataField.CONDUCTOR to setOf("CONDUCTOR"),
        MetadataField.PERFORMER to setOf("PERFORMER"),
        MetadataField.PRODUCER to setOf("PRODUCER"),
        MetadataField.COMMENT to setOf("COMMENT"),
        MetadataField.COPYRIGHT to setOf("COPYRIGHT"),
        MetadataField.LANGUAGE to setOf("LANGUAGE"),
        MetadataField.PUBLISHER to setOf("PUBLISHER"),
        MetadataField.GROUPING to setOf("GROUPING"),
        MetadataField.MOOD to setOf("MOOD"),
        MetadataField.LYRICS to setOf("LYRICS", "UNSYNCEDLYRICS"),
    ) + MetadataMusicBrainzTagMap.flacKeys
    /** The stage writer owns the authoritative Vorbis alias mapping for unchanged-key audits. */
    fun preservationAliases(field: MetadataField): Set<String> = fieldKeys[field].orEmpty()
    private val canonicalKeys = mapOf(
        MetadataField.ALBUM_ARTIST to "ALBUMARTIST",
        MetadataField.RELEASE_DATE to "DATE",
        MetadataField.TRACK_NUMBER to "TRACKNUMBER",
        MetadataField.TRACK_TOTAL to "TRACKTOTAL",
        MetadataField.DISC_NUMBER to "DISCNUMBER",
        MetadataField.DISC_TOTAL to "DISCTOTAL",
        MetadataField.LABEL to "LABEL",
        MetadataField.LYRICS to "LYRICS",
    ) + MetadataMusicBrainzTagMap.flacKeys.mapValues { it.value.first() }

    private data class Block(val type: Int, val data: ByteArray)
    private data class Entry(val raw: ByteArray, val normalizedKey: String)
    private data class Comment(val vendor: ByteArray, val entries: List<Entry>)

    private fun uint(b: Byte): Int = b.toInt() and 255
    private fun readIntLE(b: ByteArray, pos: Int): Long =
        (uint(b[pos]).toLong() or (uint(b[pos + 1]).toLong() shl 8) or
            (uint(b[pos + 2]).toLong() shl 16) or (uint(b[pos + 3]).toLong() shl 24))

    private fun addIntLE(out: ByteArrayOutputStream, value: Int) {
        repeat(4) { out.write((value ushr (8 * it)) and 255) }
    }

    private fun strictUtf8(input: ByteArray, start: Int, size: Int): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(input, start, size)).toString()
    } catch (_: Exception) { throw Rejected(Code.INVALID_UTF8) }

    private fun readComment(bytes: ByteArray): Comment {
        var pos = 0
        fun take(): ByteArray {
            if (pos + 4 > bytes.size) throw Rejected(Code.INVALID_COMMENT)
            val size = readIntLE(bytes, pos)
            pos += 4
            if (size > MAX_BLOCK || pos.toLong() + size > bytes.size)
                throw Rejected(Code.INVALID_COMMENT)
            val result = bytes.copyOfRange(pos, pos + size.toInt())
            pos += size.toInt()
            return result
        }
        val vendor = take()
        if (pos + 4 > bytes.size) throw Rejected(Code.INVALID_COMMENT)
        val count = readIntLE(bytes, pos)
        pos += 4
        if (count > MAX_COMMENTS) throw Rejected(Code.TOO_MANY_COMMENTS)
        val entries = ArrayList<Entry>(count.toInt())
        repeat(count.toInt()) {
            val raw = take()
            val index = raw.indexOf('='.code.toByte())
            if (index <= 0 || index > 128) throw Rejected(Code.INVALID_COMMENT)
            val keyBytes = raw.copyOfRange(0, index)
            if (keyBytes.any { uint(it) < 0x20 || uint(it) > 0x7e })
                throw Rejected(Code.INVALID_COMMENT)
            val key = String(keyBytes, StandardCharsets.US_ASCII).uppercase(Locale.ROOT)
            entries += Entry(raw, key)
        }
        if (pos != bytes.size) throw Rejected(Code.INVALID_COMMENT)
        return Comment(vendor, entries)
    }

    private fun commentBytes(input: Comment, diffs: List<MetadataFieldDiff>,
                             before: MetadataSnapshot): ByteArray {
        val byKey = diffs.associateBy { it.field }
        // Prove that each edited field is exactly the one the current cached FLAC
        // bytes actually hold. Aliases and duplicate entries are compared as a LIST.
        for (diff in diffs) {
            val keys = fieldKeys[diff.field] ?: throw Rejected(Code.FIELD_UNSUPPORTED)
            val found = input.entries.filter { it.normalizedKey in keys }.map { entry ->
                val separator = entry.raw.indexOf('='.code.toByte())
                strictUtf8(entry.raw, separator + 1, entry.raw.size - separator - 1)
            }
            if (diff.field in MetadataMusicBrainzTagMap.flacKeys &&
                (!MetadataMusicBrainzTagMap.valid(diff.field, diff.after) ||
                    !MetadataMusicBrainzTagMap.valid(diff.field, found)))
                throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
            if (found != before.known[diff.field].orEmpty() || found != diff.before)
                throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
        }
        val newEntries = mutableListOf<Entry>()
        val emitted = mutableSetOf<MetadataField>()
        val keyOwner = fieldKeys.flatMap { (field, keys) -> keys.map { it to field } }.toMap()
        fun emit(field: MetadataField) {
            if (!emitted.add(field)) return
            val diff = byKey[field] ?: return
            val canonical = canonicalKeys[field] ?: fieldKeys.getValue(field).first()
            for (value in diff.after) {
                val encoded = "$canonical=$value".toByteArray(StandardCharsets.UTF_8)
                if (encoded.size > MAX_BLOCK) throw Rejected(Code.STAGED_TAG_TOO_LARGE)
                newEntries += Entry(encoded, canonical)
            }
        }
        for (entry in input.entries) {
            val field = keyOwner[entry.normalizedKey]
            if (field != null && field in byKey) emit(field)
            else newEntries += entry // all unrelated bytes preserved verbatim
        }
        for (field in byKey.keys) emit(field)
        if (newEntries.size > MAX_COMMENTS) throw Rejected(Code.TOO_MANY_COMMENTS)
        val out = ByteArrayOutputStream()
        addIntLE(out, input.vendor.size)
        out.write(input.vendor)
        addIntLE(out, newEntries.size)
        for (entry in newEntries) {
            addIntLE(out, entry.raw.size)
            out.write(entry.raw)
            if (out.size() > MAX_BLOCK) throw Rejected(Code.STAGED_TAG_TOO_LARGE)
        }
        return out.toByteArray()
    }

    /** sourceCache MUST be a private snapshot, never the live content URI. */
    fun stage(
        sourceCache: File,
        staging: File,
        review: MetadataReview,
        expectedCacheBytes: Long,
        checkCancelled: () -> Unit = {},
    ): Result {
        checkCancelled()
        val ready = review as? MetadataReview.Ready ?: throw Rejected(Code.NOT_A_READY_REVIEW)
        if (MetadataReviewIntegrityPolicy.inspect(ready) !=
            MetadataReviewIntegrityPolicy.Verdict.Exact)
            throw Rejected(Code.NOT_A_READY_REVIEW)
        if (!ready.original.tagsVerified || ready.diffs.isEmpty() ||
            ready.diffs.any { it.field !in ready.original.verifiedFields })
            throw Rejected(Code.INPUT_NOT_VERIFIED)
        if (ready.diffs.any { it.field !in MetadataCapabilityRegistry.flacStageFields })
            throw Rejected(Code.FIELD_UNSUPPORTED)
        if (expectedCacheBytes !in 12..MAX_SOURCE || !sourceCache.isFile ||
            sourceCache.length() != expectedCacheBytes ||
            Files.isSymbolicLink(sourceCache.toPath()) || Files.isSymbolicLink(staging.toPath()) ||
            sourceCache.canonicalPath == staging.canonicalPath)
            throw Rejected(Code.SOURCE_NOT_SAFE)
        if (staging.exists()) throw Rejected(Code.STAGING_ALREADY_EXISTS)
        val originalModified = sourceCache.lastModified()
        val originalSize = sourceCache.length()
        val blocks = mutableListOf<Block>()
        var payloadOffset = 4L
        FileInputStream(sourceCache).use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || !magic.contentEquals("fLaC".toByteArray(StandardCharsets.US_ASCII)))
                throw Rejected(Code.INVALID_FLAC)
            var last = false
            while (!last) {
                checkCancelled()
                if (blocks.size >= MAX_BLOCKS || payloadOffset + 4 > originalSize)
                    throw Rejected(Code.INVALID_BLOCKS)
                val head = ByteArray(4)
                if (input.read(head) != 4) throw Rejected(Code.INVALID_BLOCKS)
                val length = (uint(head[1]) shl 16) or (uint(head[2]) shl 8) or uint(head[3])
                val type = uint(head[0]) and 127
                last = uint(head[0]) and 128 != 0
                payloadOffset += 4L + length
                if (length > MAX_BLOCK || payloadOffset > originalSize || payloadOffset > MAX_TOTAL_METADATA)
                    throw Rejected(Code.INVALID_BLOCKS)
                val data = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    checkCancelled()
                    val n = input.read(data, offset, length - offset)
                    if (n <= 0) throw Rejected(Code.INVALID_BLOCKS)
                    offset += n
                }
                if (blocks.isEmpty() && (type != 0 || length != 34)) throw Rejected(Code.INVALID_BLOCKS)
                blocks += Block(type, data)
            }
        }
        val commentIndices = blocks.indices.filter { blocks[it].type == 4 }
        if (commentIndices.isEmpty()) throw Rejected(Code.MISSING_COMMENT_BLOCK)
        if (commentIndices.size != 1) throw Rejected(Code.DUPLICATE_COMMENT_BLOCK)
        val commentIndex = commentIndices.single()
        val comment = readComment(blocks[commentIndex].data)
        val updatedComment = commentBytes(comment, ready.diffs, ready.original)
        if (updatedComment.size > MAX_BLOCK || updatedComment.size >= (1 shl 24))
            throw Rejected(Code.STAGED_TAG_TOO_LARGE)
        val postEdit = readComment(updatedComment)
        for (diff in ready.diffs) {
            val aliases = fieldKeys[diff.field] ?: throw Rejected(Code.FIELD_UNSUPPORTED)
            val after = postEdit.entries.filter { it.normalizedKey in aliases }.map { entry ->
                val eq = entry.raw.indexOf('='.code.toByte())
                strictUtf8(entry.raw, eq + 1, entry.raw.size - eq - 1)
            }
            if (after != diff.after) throw Rejected(Code.AMBIGUOUS_OR_STALE_TAGS)
        }
        blocks[commentIndex] = Block(4, updatedComment)
        // Reserve room for both private source cache and candidate before any output.
        if (staging.parentFile?.usableSpace?.let { it < originalSize + 32L * 1024 * 1024 } != false)
            throw Rejected(Code.INSUFFICIENT_STAGING_SPACE)
        var created = false
        try {
            FileChannel.open(staging.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                created = true
                val output = Channels.newOutputStream(channel)
                output.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
                for ((index, block) in blocks.withIndex()) {
                    checkCancelled()
                    output.write(block.type or (if (index == blocks.lastIndex) 128 else 0))
                    output.write((block.data.size ushr 16) and 255)
                    output.write((block.data.size ushr 8) and 255)
                    output.write(block.data.size and 255)
                    output.write(block.data)
                }
                FileInputStream(sourceCache).use { input ->
                    var remaining = payloadOffset
                    while (remaining > 0) {
                        checkCancelled()
                        val skipped = input.skip(remaining)
                        if (skipped <= 0) throw Rejected(Code.SOURCE_CHANGED)
                        remaining -= skipped
                    }
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) output.write(buffer, 0, n)
                    }
                }
                channel.force(true)
            }
            checkCancelled()
            if (sourceCache.length() != originalSize || sourceCache.lastModified() != originalModified)
                throw Rejected(Code.SOURCE_CHANGED)
            return Result(fieldsChanged = ready.diffs.mapTo(linkedSetOf()) { it.field },
                originalBytes = originalSize, stagedBytes = staging.length(),
                audioBytesCopied = originalSize - payloadOffset)
        } catch (error: Exception) {
            if (created) staging.delete() // never delete or overwrite an existing file
            throw error
        }
    }
}
