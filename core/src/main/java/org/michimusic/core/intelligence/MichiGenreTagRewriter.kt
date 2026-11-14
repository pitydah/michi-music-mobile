package org.michimusic.core.intelligence

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * MI-23: deterministic, off-device-testable genre-only binary transformer.
 * Requires a distinct staging destination; NEVER writes the source file in place.
 * Supports conservative ID3v2.3/2.4 (without unsync/extended-header/footer) and
 * FLAC with exactly one Vorbis Comment block. Any ambiguity is UNSUPPORTED.
 * Ogg/Opus, MP4, WavPack, ID3v1-only and unsupported ID3 flags are read-only.
 * Only call after explicit user approval; the Android gateway owns permissions,
 * durable backup, rollback, file identity checks and post-write reinspection.
 */
object MichiGenreTagRewriter {
    class UnsupportedFormat(message: String) : Exception(message)
    private const val MAX_TAG = 16 * 1024 * 1024
    private const val MAX_FILE = 1024L * 1024 * 1024 // Initial explicit-edit cap: 1 GiB
    private const val COPY_CHUNK = 64 * 1024

    data class Result(val format: String, val oldGenres: List<String>, val newGenres: List<String>,
        val sourceBytes: Long, val outputBytes: Long)

    /** Staging must be a separate file, not a symlink/alias of the source. */
    fun rewrite(source: File, staging: File, genres: List<String>): Result {
        val safe = MichiGenreNormalizationPolicy.validateGenres(genres)
            ?: throw UnsupportedFormat("INVALID_GENRES")
        if (!source.isFile || source.length() < 12 || source.length() > MAX_FILE ||
            source.canonicalPath == staging.canonicalPath || staging.exists())
            throw UnsupportedFormat("UNSAFE_SOURCE_OR_STAGING")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(staging).use { output ->
                    val magic = ByteArray(10)
                    if (input.read(magic) != 10) throw UnsupportedFormat("TRUNCATED_FILE")
                    val result = when {
                        String(magic, 0, 4, StandardCharsets.US_ASCII) == "fLaC" ->
                            rewriteFlac(source, output, safe)
                        String(magic, 0, 3, StandardCharsets.US_ASCII) == "ID3" ->
                            rewriteMp3(source, output, safe)
                        else -> throw UnsupportedFormat("FORMAT_NOT_CERTIFIED")
                    }
                    output.fd.sync()
                    return result.copy(outputBytes = staging.length())
                }
            }
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }

    private fun u(b: Byte) = b.toInt() and 255
    private fun le32(b: ByteArray, p: Int): Int = u(b[p]) or (u(b[p+1]) shl 8) or
        (u(b[p+2]) shl 16) or (u(b[p+3]) shl 24)
    private fun be32(b: ByteArray, p: Int): Int = (u(b[p]) shl 24) or (u(b[p+1]) shl 16) or
        (u(b[p+2]) shl 8) or u(b[p+3])
    private fun sync32(b: ByteArray, p: Int): Int {
        if ((0..3).any { u(b[p+it]) >= 128 }) throw UnsupportedFormat("INVALID_SYNCHSAFE")
        return (0..3).fold(0) { value, i -> (value shl 7) or u(b[p+i]) }
    }
    private fun putLe(out: ByteArrayOutputStream, n: Int) {
        for(i in 0..3) out.write((n ushr (8*i)) and 255)
    }
    private fun putBe(out: ByteArrayOutputStream, n: Int) {
        for(i in 3 downTo 0) out.write((n ushr (8*i)) and 255)
    }
    private fun syncBytes(n: Int): ByteArray {
        if (n < 0 || n >= (1 shl 28)) throw UnsupportedFormat("ID3_SIZE_OVERFLOW")
        return byteArrayOf(((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
            ((n ushr 7) and 127).toByte(), (n and 127).toByte())
    }
    private fun readFully(input: FileInputStream, result: ByteArray) {
        var pos = 0
        while (pos < result.size) {
            val n = input.read(result, pos, result.size-pos)
            if (n <= 0) throw UnsupportedFormat("TRUNCATED_METADATA_BLOCK")
            pos += n
        }
    }
    private fun copyTail(source: File, out: FileOutputStream, offset: Long) {
        FileInputStream(source).use { input ->
            var remaining = offset
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) throw UnsupportedFormat("UNSEEKABLE_SOURCE")
                remaining -= skipped
            }
            val buf = ByteArray(COPY_CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) out.write(buf, 0, n)
            }
        }
    }

    private data class Block(val type: Int, val data: ByteArray)
    private fun rewriteFlac(source: File, out: FileOutputStream, genres: List<String>): Result {
        val blocks = ArrayList<Block>()
        var offset = 4L
        var last = false
        var comments = 0
        FileInputStream(source).use { input ->
            if (input.skip(4) != 4L) throw UnsupportedFormat("BAD_FLAC_HEADER")
            while (!last) {
                if (blocks.size >= 64 || offset + 4 > source.length()) throw UnsupportedFormat("BAD_FLAC_BLOCKS")
                val header = ByteArray(4)
                if (input.read(header) != 4) throw UnsupportedFormat("BAD_FLAC_BLOCK_HEADER")
                offset += 4
                last = u(header[0]) and 128 != 0
                val kind = u(header[0]) and 127
                val size = (u(header[1]) shl 16) or (u(header[2]) shl 8) or u(header[3])
                if (size > MAX_TAG || offset + size > source.length()) throw UnsupportedFormat("BAD_FLAC_BLOCK_SIZE")
                val data = ByteArray(size)
                readFully(input,data)
                offset += size
                if (blocks.isEmpty() && (kind != 0 || size != 34)) throw UnsupportedFormat("NO_STREAMINFO")
                if (kind == 4) comments++
                blocks.add(Block(kind, data))
                if (offset > MAX_TAG) throw UnsupportedFormat("FLAC_TAG_TOO_LARGE")
            }
        }
        if (comments != 1) throw UnsupportedFormat("NEED_SINGLE_VORBIS_COMMENT")
        val index = blocks.indexOfFirst { it.type == 4 }
        val (replacement, previous) = rewriteVorbisComment(blocks[index].data, genres)
        blocks[index] = Block(4, replacement)
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        for ((idx, block) in blocks.withIndex()) {
            if (block.data.size >= (1 shl 24)) throw UnsupportedFormat("FLAC_BLOCK_OVERFLOW")
            out.write(block.type or (if(idx == blocks.lastIndex) 128 else 0))
            out.write((block.data.size ushr 16) and 255)
            out.write((block.data.size ushr 8) and 255)
            out.write(block.data.size and 255)
            out.write(block.data)
        }
        copyTail(source, out, offset)
        return Result("FLAC", previous, genres, source.length(), -1L)
    }

    private fun rewriteVorbisComment(data: ByteArray, genres: List<String>): Pair<ByteArray, List<String>> {
        var p = 0
        fun take(): ByteArray {
            if (p + 4 > data.size) throw UnsupportedFormat("BROKEN_VORBIS_COMMENT")
            val length = le32(data,p); p += 4
            if (length < 0 || length > MAX_TAG || p.toLong()+length > data.size)
                throw UnsupportedFormat("BROKEN_VORBIS_COMMENT_LENGTH")
            val result = data.copyOfRange(p,p+length);p+=length;return result
        }
        val vendor = take()
        if (p+4 > data.size) throw UnsupportedFormat("BROKEN_VORBIS_COMMENT_COUNT")
        val count = le32(data,p);p += 4
        if (count !in 0..4096) throw UnsupportedFormat("BROKEN_VORBIS_COMMENT_COUNT")
        val retained = ArrayList<ByteArray>(count)
        val old = ArrayList<String>()
        repeat(count) {
            val raw = take()
            val eq = raw.indexOf('='.code.toByte())
            if (eq <= 0) throw UnsupportedFormat("BROKEN_VORBIS_FIELD")
            val key = String(raw,0,eq,StandardCharsets.US_ASCII)
            if (key.equals("GENRE",ignoreCase=true)) {
                old.add(String(raw,eq+1,raw.size-eq-1,StandardCharsets.UTF_8))
            } else retained.add(raw)
        }
        if (p != data.size) throw UnsupportedFormat("TRAILING_VORBIS_BYTES")
        val output = ByteArrayOutputStream()
        putLe(output,vendor.size); output.write(vendor)
        putLe(output,retained.size + genres.size)
        for (entry in retained + genres.map { "GENRE=$it".toByteArray(StandardCharsets.UTF_8) }) {
            putLe(output,entry.size);output.write(entry)
        }
        return output.toByteArray().takeIf { it.size <= MAX_TAG }
            ?.let { it to old } ?: throw UnsupportedFormat("VORBIS_SIZE_OVERFLOW")
    }

    private fun rewriteMp3(source: File, out: FileOutputStream, genres: List<String>): Result {
        if (source.length() >= 128L) RandomAccessFile(source, "r").use { raf ->
            raf.seek(source.length() - 128L)
            val legacy = ByteArray(3)
            raf.readFully(legacy)
            if (String(legacy,StandardCharsets.US_ASCII)=="TAG")
                throw UnsupportedFormat("ID3V1_GENRE_MAY_CONFLICT")
        }
        val header = FileInputStream(source).use { input ->
            ByteArray(10).also { readFully(input,it) }
        }
        val version = u(header[3]); val flags = u(header[5])
        if (version !in 3..4 || flags != 0) throw UnsupportedFormat("UNSUPPORTED_ID3_FLAGS_OR_VERSION")
        // ID3v2.3 cannot represent arbitrary multiple distinct values reliably.
        if (version == 3 && genres.size != 1) throw UnsupportedFormat("ID3V23_SINGLE_GENRE_ONLY")
        val tagSize = sync32(header,6)
        if (tagSize < 0 || tagSize > MAX_TAG || tagSize.toLong() + 10 > source.length())
            throw UnsupportedFormat("ID3_TAG_TOO_LARGE")
        val contents = FileInputStream(source).use { input ->
            if (input.skip(10) != 10L) throw UnsupportedFormat("SHORT_ID3")
            ByteArray(tagSize).also { readFully(input,it) }
        }
        val kept = ByteArrayOutputStream()
        val old = ArrayList<String>()
        var at = 0
        while (at < contents.size) {
            if (contents.size-at < 10) {
                if (contents.copyOfRange(at,contents.size).any { it != 0.toByte() })
                    throw UnsupportedFormat("INVALID_ID3_PADDING")
                break
            }
            if ((0..3).all { contents[at+it] == 0.toByte() }) {
                if (contents.copyOfRange(at,contents.size).any { it != 0.toByte() })
                    throw UnsupportedFormat("INVALID_ID3_PADDING")
                break
            }
            val name = String(contents,at,4,StandardCharsets.US_ASCII)
            if (!name.matches(Regex("[A-Z0-9]{4}"))) throw UnsupportedFormat("INVALID_ID3_FRAME")
            val size = if(version==4) sync32(contents,at+4) else be32(contents,at+4)
            if (size <= 0 || at.toLong()+10+size > contents.size)
                throw UnsupportedFormat("INVALID_ID3_FRAME_SIZE")
            val end=at+10+size
            if (name == "TCON") {
                if (u(contents[at+8]) != 0 || u(contents[at+9]) != 0)
                    throw UnsupportedFormat("UNSUPPORTED_TCON_FLAGS")
                // Existing genre is displayed from the local, verified reader;
                // record this frame only as nonauthoritative diagnostics.
                old.add("<existing TCON>")
            } else kept.write(contents,at,end-at) // byte-for-byte preservation of every other frame
            at=end
        }
        val encoded = if(version==4) {
            byteArrayOf(3) + genres.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
        } else {
            byteArrayOf(1,0xFF.toByte(),0xFE.toByte()) +
                genres.single().toByteArray(StandardCharsets.UTF_16LE)
        }
        kept.write("TCON".toByteArray(StandardCharsets.US_ASCII))
        if (version==4) kept.write(syncBytes(encoded.size)) else putBe(kept, encoded.size)
        kept.write(0);kept.write(0)
        kept.write(encoded)
        val tag = kept.toByteArray()
        if (tag.size > MAX_TAG) throw UnsupportedFormat("ID3_TAG_TOO_LARGE")
        out.write(header,0,6)
        out.write(syncBytes(tag.size))
        out.write(tag)
        copyTail(source,out,10L+tagSize)
        return Result("MP3-ID3v2.$version",old,genres,source.length(),-1L)
    }
}
