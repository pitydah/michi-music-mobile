package org.michimusic.data.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sample-equivalence contract of [MetadataM4aAudioOnlyInspector].
 *
 * The certification must reject a document whose sample table points outside
 * the recorded media payload, and it must accept a byte-identical media stream
 * that a rewrite relocated to a different absolute offset. That is proven here
 * by comparing the sample and timeline fingerprints across the relocation while
 * the chunk-offset fingerprint changes.
 */
class MetadataM4aAudioOnlyInspectorTest {
    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte(),
    )

    private fun box(type: String, payload: ByteArray): ByteArray =
        u32((8 + payload.size).toLong()) +
            type.toByteArray(StandardCharsets.US_ASCII) + payload

    private fun zeros(count: Int): ByteArray = ByteArray(count)

    private fun stsd(codec: String): ByteArray = box(
        "stsd",
        zeros(4) + u32(1) + u32(36) +
            codec.toByteArray(StandardCharsets.US_ASCII) + zeros(28),
    )

    private fun stts(count: Long, delta: Long): ByteArray =
        box("stts", zeros(4) + u32(1) + u32(count) + u32(delta))

    private fun ctts(count: Long): ByteArray =
        box("ctts", zeros(4) + u32(1) + u32(count) + u32(0))

    private fun stsc(samplesPerChunk: Long): ByteArray =
        box("stsc", zeros(4) + u32(1) + u32(1) + u32(samplesPerChunk) + u32(1))

    private fun stsz(sizes: List<Long>): ByteArray =
        box(
            "stsz",
            zeros(4) + u32(0) + u32(sizes.size.toLong()) +
                sizes.fold(ByteArray(0)) { acc, size -> acc + u32(size) },
        )

    private fun stz2(sizes: List<Long>): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(zeros(4))
            write(zeros(3))
            write(8)
            write(u32(sizes.size.toLong()))
            sizes.forEach { write(it.toInt()) }
        }
        return box("stz2", body.toByteArray())
    }

    private fun stco(chunkOffset: Long): ByteArray =
        box("stco", zeros(4) + u32(1) + u32(chunkOffset))

    private fun elst(mediaTime: Long): ByteArray =
        box("elst", zeros(4) + u32(1) + u32(1_000) + u32(mediaTime) + u32(0x0001_0000))

    private fun mdhd(timescale: Long): ByteArray =
        box("mdhd", zeros(4) + u32(0) + u32(0) + u32(timescale) + u32(0) +
            byteArrayOf(0x55, 0xC4.toByte()) + zeros(2))

    private fun hdlr(handler: String): ByteArray =
        box("hdlr", zeros(4) + u32(0) +
            handler.toByteArray(StandardCharsets.US_ASCII) + zeros(12))

    private fun trak(
        chunkOffset: Long,
        sampleSizes: List<Long>,
        compact: Boolean,
        compositionOffsets: Boolean,
        withEditList: Boolean,
        stsdBytes: ByteArray = stsd("mp4a"),
        handler: String = "soun",
    ): ByteArray {
        val stbl = box(
            "stbl",
            stsdBytes +
                stts(sampleSizes.size.toLong(), 1_024L) +
                (if (compositionOffsets) ctts(sampleSizes.size.toLong()) else ByteArray(0)) +
                stsc(sampleSizes.size.toLong()) +
                (if (compact) stz2(sampleSizes) else stsz(sampleSizes)) +
                stco(chunkOffset),
        )
        val minf = box("minf", box("smhd", zeros(8)) + stbl)
        val mdia = box("mdia", hdlr(handler) + mdhd(44_100L) + minf)
        val editList = if (withEditList) box("edts", elst(0L)) else ByteArray(0)
        return box("trak", box("tkhd", zeros(84)) + editList + mdia)
    }

    private fun stsdMultiple(vararg codecs: String): ByteArray = box(
        "stsd",
        zeros(4) + u32(codecs.size.toLong()) +
            codecs.fold(ByteArray(0)) { acc, codec ->
                acc + u32(36) + codec.toByteArray(StandardCharsets.US_ASCII) + zeros(28)
            },
    )

    private fun prefix(
        freeBeforeMdat: Int,
        chunkOffset: Long,
        sampleSizes: List<Long>,
        compact: Boolean,
        compositionOffsets: Boolean,
        withEditList: Boolean,
    ): ByteArray {
        val ftyp = box(
            "ftyp",
            "M4A ".toByteArray(StandardCharsets.US_ASCII) + zeros(8),
        )
        val free = if (freeBeforeMdat > 0) box("free", zeros(freeBeforeMdat - 8))
        else ByteArray(0)
        val moov = box(
            "moov",
            trak(chunkOffset, sampleSizes, compact, compositionOffsets, withEditList),
        )
        return ftyp + free + moov
    }

    private fun audioOnlyM4a(
        freeBeforeMdat: Int = 0,
        overrideOffset: Long? = null,
        sampleSizes: List<Long> = listOf(4L, 4L, 4L),
        mediaBytes: Int = -1,
        compact: Boolean = false,
        compositionOffsets: Boolean = false,
        withEditList: Boolean = false,
    ): ByteArray {
        val skeleton = prefix(
            freeBeforeMdat, 0L, sampleSizes, compact, compositionOffsets, withEditList,
        )
        val chunkOffset = overrideOffset ?: (skeleton.size.toLong() + 8L)
        val real = prefix(
            freeBeforeMdat, chunkOffset, sampleSizes, compact, compositionOffsets,
            withEditList,
        )
        check(real.size == skeleton.size) { "prefix size must not depend on the offset" }
        val payload = if (mediaBytes >= 0) mediaBytes else sampleSizes.sumOf { it.toInt() }
        return real + box("mdat", zeros(payload))
    }

    private fun m4aWithTracks(
        sampleSizes: List<Long>,
        tracks: (Long) -> List<ByteArray>,
    ): ByteArray {
        val ftyp = box(
            "ftyp",
            "M4A ".toByteArray(StandardCharsets.US_ASCII) + zeros(8),
        )
        fun moov(offset: Long) = box(
            "moov",
            tracks(offset).fold(ByteArray(0)) { acc, bytes -> acc + bytes },
        )
        val skeleton = ftyp + moov(0L)
        val chunkOffset = skeleton.size.toLong() + 8L
        val real = ftyp + moov(chunkOffset)
        check(real.size == skeleton.size) { "trak size must not depend on the offset" }
        return real + box("mdat", zeros(sampleSizes.sumOf { it.toInt() }))
    }

    private fun inspect(bytes: ByteArray): MetadataM4aAudioOnlyInspector.Result {
        val file = File.createTempFile("michi-m4a-inspector-", ".m4a")
        try {
            file.writeBytes(bytes)
            return RandomAccessFile(file, "r").use { handle ->
                handle.channel.use { MetadataM4aAudioOnlyInspector.inspect(it) }
            }
        } finally {
            file.delete()
        }
    }

    private fun accepted(bytes: ByteArray): MetadataM4aAudioOnlyInspector.Evidence {
        val result = inspect(bytes)
        assertTrue(
            "expected AudioOnly, got $result",
            result is MetadataM4aAudioOnlyInspector.Result.AudioOnly,
        )
        return (result as MetadataM4aAudioOnlyInspector.Result.AudioOnly).evidence
    }

    @Test fun rejectsInvalidSampleOffsetsAndAcceptsEquivalentRelocation() {
        val baseline = accepted(audioOnlyM4a())
        assertEquals(1, baseline.audioTracks)
        assertEquals("mp4a", baseline.codec)
        assertEquals(3L, baseline.samples.sampleCount)
        assertEquals(12L, baseline.samples.sampleBytes)
        assertEquals(12L, baseline.mediaPayloadBytes)
        assertEquals(64, baseline.mediaPayloadSha256.length)
        assertEquals(64, baseline.chunkOffsetSha256.length)
        assertEquals(64, baseline.samples.fingerprintSha256.length)

        // Same media and timeline, 24 bytes later: only the offset table moved.
        val relocated = accepted(audioOnlyM4a(freeBeforeMdat = 24))
        assertEquals(baseline.samples.sampleCount, relocated.samples.sampleCount)
        assertEquals(baseline.samples.sampleBytes, relocated.samples.sampleBytes)
        assertEquals(baseline.samples.samplesSha256, relocated.samples.samplesSha256)
        assertEquals(baseline.samples.timelineSha256, relocated.samples.timelineSha256)
        assertEquals(
            baseline.samples.fingerprintSha256,
            relocated.samples.fingerprintSha256,
        )
        assertEquals(baseline.mediaPayloadSha256, relocated.mediaPayloadSha256)
        assertNotEquals(baseline.chunkOffsetSha256, relocated.chunkOffsetSha256)

        // Compact 8-bit sample sizes describe the same samples.
        val compact = accepted(audioOnlyM4a(compact = true))
        assertEquals(baseline.samples.samplesSha256, compact.samples.samplesSha256)
        assertEquals(baseline.samples.timelineSha256, compact.samples.timelineSha256)

        // Composition offsets and an edit list belong to the timeline evidence.
        val timed = accepted(audioOnlyM4a(compositionOffsets = true, withEditList = true))
        assertEquals(baseline.samples.samplesSha256, timed.samples.samplesSha256)
        assertNotEquals(baseline.samples.timelineSha256, timed.samples.timelineSha256)

        // A chunk offset that lands outside every mdat payload is refused.
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("SAMPLE_OUTSIDE_MDAT"),
            inspect(audioOnlyM4a(overrideOffset = 12L)),
        )

        // A declared sample that overruns the recorded payload is refused.
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("SAMPLE_OUTSIDE_MDAT"),
            inspect(
                audioOnlyM4a(sampleSizes = listOf(4L, 4L, 4_096L), mediaBytes = 12),
            ),
        )
    }

    @Test fun rejectsVideoTrackPresent() {
        val sizes = listOf(4L, 4L, 4L)
        val bytes = m4aWithTracks(sizes) { offset ->
            listOf(
                trak(offset, sizes, compact = false, compositionOffsets = false, withEditList = false),
                trak(
                    offset, sizes, compact = false, compositionOffsets = false,
                    withEditList = false, handler = "vide",
                ),
            )
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("VIDEO_TRACK_PRESENT"),
            inspect(bytes),
        )
    }

    @Test fun rejectsEncryptedSampleDescription() {
        val sizes = listOf(4L, 4L, 4L)
        val bytes = m4aWithTracks(sizes) { offset ->
            listOf(
                trak(
                    offset, sizes, compact = false, compositionOffsets = false,
                    withEditList = false, stsdBytes = stsd("enca"),
                ),
            )
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("ENCRYPTED_TRACK"),
            inspect(bytes),
        )
    }

    @Test fun rejectsMultipleSampleDescriptions() {
        val sizes = listOf(4L, 4L, 4L)
        val bytes = m4aWithTracks(sizes) { offset ->
            listOf(
                trak(
                    offset, sizes, compact = false, compositionOffsets = false,
                    withEditList = false, stsdBytes = stsdMultiple("mp4a", "enca"),
                ),
            )
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("MULTIPLE_SAMPLE_DESCRIPTIONS"),
            inspect(bytes),
        )
    }

    @Test fun rejectsFragmentedMp4() {
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("FRAGMENTED_MP4_NOT_CERTIFIED"),
            inspect(audioOnlyM4a() + box("moof", zeros(8))),
        )
    }

    @Test fun rejectsAtomLimit() {
        val free = box("free", ByteArray(0))
        val crowded = ByteArrayOutputStream().apply {
            write(audioOnlyM4a())
            repeat(16_384) { write(free) }
        }.toByteArray()
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("ATOM_LIMIT"),
            inspect(crowded),
        )
    }

    @Test fun acceptsMoovAfterMdat() {
        val sizes = listOf(4L, 4L, 4L)
        val ftyp = box(
            "ftyp",
            "M4A ".toByteArray(StandardCharsets.US_ASCII) + zeros(8),
        )
        val mdat = box("mdat", zeros(12))
        val chunkOffset = (ftyp.size + 8).toLong()
        val moov = box(
            "moov",
            trak(chunkOffset, sizes, compact = false, compositionOffsets = false,
                withEditList = false),
        )
        val evidence = accepted(ftyp + mdat + moov)
        assertEquals(3L, evidence.samples.sampleCount)
        assertEquals(12L, evidence.mediaPayloadBytes)
    }
}
