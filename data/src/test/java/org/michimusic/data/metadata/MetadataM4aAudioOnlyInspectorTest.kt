package org.michimusic.data.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Sample-integrity contract of [MetadataM4aAudioOnlyInspector].
 *
 * The certification must reject a document whose sample table points outside
 * the recorded media payload, a duplicate/nonexistent sample reference or an
 * inconsistent chunk mapping, and it must accept a byte-equivalent media stream
 * that a rewrite relocated to a different absolute offset. Sample equality is
 * proven over the actual bytes each table entry references, so swapping valid
 * chunk offsets or reordering samples changes the evidence even when the
 * `mdat` payload and the declared sizes stay identical.
 */
class MetadataM4aAudioOnlyInspectorTest {
    private fun u16(value: Int): ByteArray =
        byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte(),
    )

    private fun u64(value: Long): ByteArray = ByteArray(8) { index ->
        (value ushr (56 - index * 8)).toByte()
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        u32((8 + payload.size).toLong()) +
            type.toByteArray(StandardCharsets.US_ASCII) + payload

    private fun zeros(count: Int): ByteArray = ByteArray(count)

    private fun stsd(
        codec: String,
        dataReferenceIndex: Int = 1,
        sampleEntryVersion: Int = 0,
        children: ByteArray = ByteArray(0),
    ): ByteArray {
        val extra = when (sampleEntryVersion) {
            0 -> 0
            1 -> 16
            2 -> 36
            else -> error("unsupported synthetic sample-entry version")
        }
        val entry = zeros(6) + u16(dataReferenceIndex) + u16(sampleEntryVersion) +
            zeros(18) + zeros(extra) + children
        return box(
            "stsd",
            zeros(4) + u32(1) + box(codec, entry),
        )
    }

    private fun dref(selfContained: Boolean = true): ByteArray = box(
        "dref",
        zeros(4) + u32(1) + box("url ", u32(if (selfContained) 1L else 0L)),
    )

    private fun dinf(selfContained: Boolean = true): ByteArray =
        box("dinf", dref(selfContained))

    private fun stts(count: Long, delta: Long): ByteArray =
        box("stts", zeros(4) + u32(1) + u32(count) + u32(delta))

    private fun ctts(count: Long): ByteArray =
        box("ctts", zeros(4) + u32(1) + u32(count) + u32(0))

    private fun stsc(samplesPerChunk: Long, description: Long = 1L): ByteArray =
        box("stsc", zeros(4) + u32(1) + u32(1) + u32(samplesPerChunk) + u32(description))

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

    private fun stco(offsets: List<Long>): ByteArray = box(
        "stco",
        zeros(4) + u32(offsets.size.toLong()) +
            offsets.fold(ByteArray(0)) { acc, offset -> acc + u32(offset) },
    )

    private fun co64(offsets: List<Long>): ByteArray = box(
        "co64",
        zeros(4) + u32(offsets.size.toLong()) +
            offsets.fold(ByteArray(0)) { acc, offset -> acc + u64(offset) },
    )

    private fun stco(chunkOffset: Long): ByteArray = stco(listOf(chunkOffset))

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
        trackId: Long = 1L,
        drefBytes: ByteArray = dinf(),
        chunkOffsets: List<Long>? = null,
        stscBytes: ByteArray? = null,
        sttsBytes: ByteArray? = null,
        co64Table: Boolean = false,
    ): ByteArray {
        val offsets = chunkOffsets ?: listOf(chunkOffset)
        val stbl = box(
            "stbl",
            stsdBytes +
                (sttsBytes ?: stts(sampleSizes.size.toLong(), 1_024L)) +
                (if (compositionOffsets) ctts(sampleSizes.size.toLong()) else ByteArray(0)) +
                (stscBytes ?: stsc(sampleSizes.size.toLong())) +
                (if (compact) stz2(sampleSizes) else stsz(sampleSizes)) +
                (if (co64Table) co64(offsets) else stco(offsets)),
        )
        val minf = box("minf", box("smhd", zeros(8)) + drefBytes + stbl)
        val mdia = box("mdia", hdlr(handler) + mdhd(44_100L) + minf)
        val editList = if (withEditList) box("edts", elst(0L)) else ByteArray(0)
        return box(
            "trak",
            box("tkhd", zeros(12) + u32(trackId) + zeros(68)) + editList + mdia,
        )
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
        co64Table: Boolean = false,
    ): ByteArray {
        val ftyp = box(
            "ftyp",
            "M4A ".toByteArray(StandardCharsets.US_ASCII) + zeros(8),
        )
        val free = if (freeBeforeMdat > 0) box("free", zeros(freeBeforeMdat - 8))
        else ByteArray(0)
        val moov = box(
            "moov",
            trak(
                chunkOffset,
                sampleSizes,
                compact,
                compositionOffsets,
                withEditList,
                co64Table = co64Table,
            ),
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
        co64Table: Boolean = false,
    ): ByteArray {
        val skeleton = prefix(
            freeBeforeMdat, 0L, sampleSizes, compact, compositionOffsets,
            withEditList, co64Table,
        )
        val chunkOffset = overrideOffset ?: (skeleton.size.toLong() + 8L)
        val real = prefix(
            freeBeforeMdat, chunkOffset, sampleSizes, compact, compositionOffsets,
            withEditList, co64Table,
        )
        check(real.size == skeleton.size) { "prefix size must not depend on the offset" }
        val payload = if (mediaBytes >= 0) mediaBytes else sampleSizes.sumOf { it.toInt() }
        return real + box("mdat", zeros(payload))
    }

    private fun m4aWithTracks(
        sampleSizes: List<Long>,
        mediaPayload: ByteArray? = null,
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
        val payload = mediaPayload ?: zeros(sampleSizes.sumOf { it.toInt() })
        return real + box("mdat", payload)
    }

    private fun twoChunkM4a(
        sampleSizes: List<Long>,
        mediaPayload: ByteArray,
        relativeOffsets: List<Long>,
        stscBytes: ByteArray = stsc(1),
    ): ByteArray = m4aWithTracks(sampleSizes, mediaPayload) { mdat ->
        listOf(
            trak(
                mdat,
                sampleSizes,
                compact = false,
                compositionOffsets = false,
                withEditList = false,
                chunkOffsets = relativeOffsets.map { mdat + it },
                stscBytes = stscBytes,
            ),
        )
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

    private fun fixture(): File {
        val candidates = listOf(
            File("src/androidTest/assets/me14-audio-only.m4a"),
            File("data/src/androidTest/assets/me14-audio-only.m4a"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("the real AAC fixture is not available")
    }

    private fun feedLong(digest: MessageDigest, value: Long) {
        digest.update(
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array(),
        )
    }

    private fun feedText(digest: MessageDigest, text: String) {
        digest.update(text.toByteArray(StandardCharsets.US_ASCII))
        digest.update(0.toByte())
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
        assertEquals(64, baseline.samples.samplesSha256.length)
        assertEquals(64, baseline.samples.codecConfigSha256.length)
        assertEquals(64, baseline.samples.timelineSha256.length)
        assertEquals(64, baseline.samples.fingerprintSha256.length)
        val baselineTrack = baseline.samples.tracks.single()
        assertEquals(1L, baselineTrack.trackId)
        assertEquals("mp4a", baselineTrack.codec)
        assertEquals(1, baselineTrack.dataReferenceIndex)
        assertEquals(44_100L, baselineTrack.timeScale)
        assertEquals(3L, baselineTrack.sampleCount)
        assertEquals(12L, baselineTrack.sampleBytes)
        assertEquals(3L * 1_024L, baselineTrack.decodeDuration)
        assertEquals(0L, baselineTrack.compositionEntries)
        assertTrue(baselineTrack.edits.isEmpty())

        // Same media and timeline, 24 bytes later: only the offset table moved.
        val relocated = accepted(audioOnlyM4a(freeBeforeMdat = 24))
        assertEquals(baseline.samples.sampleCount, relocated.samples.sampleCount)
        assertEquals(baseline.samples.sampleBytes, relocated.samples.sampleBytes)
        assertEquals(baseline.samples.samplesSha256, relocated.samples.samplesSha256)
        assertEquals(baseline.samples.timelineSha256, relocated.samples.timelineSha256)
        assertEquals(baseline.samples.codecConfigSha256, relocated.samples.codecConfigSha256)
        assertEquals(
            baseline.samples.fingerprintSha256,
            relocated.samples.fingerprintSha256,
        )
        assertEquals(baseline.mediaPayloadSha256, relocated.mediaPayloadSha256)
        assertNotEquals(baseline.chunkOffsetSha256, relocated.chunkOffsetSha256)

        // 64-bit chunk offsets keep the same actual sample evidence.
        val co64 = accepted(audioOnlyM4a(co64Table = true))
        val co64Relocated = accepted(audioOnlyM4a(freeBeforeMdat = 24, co64Table = true))
        assertEquals(co64.samples, co64Relocated.samples)
        assertEquals(co64.mediaPayloadSha256, co64Relocated.mediaPayloadSha256)
        assertNotEquals(co64.chunkOffsetSha256, co64Relocated.chunkOffsetSha256)

        // Compact 8-bit sample sizes describe the same samples.
        val compact = accepted(audioOnlyM4a(compact = true))
        assertEquals(baseline.samples.samplesSha256, compact.samples.samplesSha256)
        assertEquals(baseline.samples.timelineSha256, compact.samples.timelineSha256)
        assertEquals(baseline.samples.codecConfigSha256, compact.samples.codecConfigSha256)

        // Composition offsets and an edit list belong to the timeline evidence.
        val timed = accepted(audioOnlyM4a(compositionOffsets = true, withEditList = true))
        assertEquals(baseline.samples.samplesSha256, timed.samples.samplesSha256)
        assertEquals(baseline.samples.codecConfigSha256, timed.samples.codecConfigSha256)
        assertNotEquals(baseline.samples.timelineSha256, timed.samples.timelineSha256)
        assertNotEquals(
            baseline.samples.fingerprintSha256,
            timed.samples.fingerprintSha256,
        )
        assertEquals(1, timed.samples.tracks.single().edits.size)

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

    @Test fun detectsSwappedChunkOffsetsAndReorderedSamples() {
        val payload = ByteArray(8) { it.toByte() }
        val baseline = accepted(
            twoChunkM4a(listOf(4L, 4L), payload, listOf(0L, 4L)),
        )
        assertEquals(8L, baseline.samples.sampleBytes)
        val swapped = accepted(
            twoChunkM4a(listOf(4L, 4L), payload, listOf(4L, 0L)),
        )
        assertEquals(baseline.mediaPayloadSha256, swapped.mediaPayloadSha256)
        assertEquals(baseline.samples.sampleBytes, swapped.samples.sampleBytes)
        assertEquals(baseline.samples.timelineSha256, swapped.samples.timelineSha256)
        assertNotEquals(baseline.samples.samplesSha256, swapped.samples.samplesSha256)
        assertNotEquals(
            baseline.samples.fingerprintSha256,
            swapped.samples.fingerprintSha256,
        )

        // Unequal sizes prove the sample boundaries move with the offset order.
        val reordered = accepted(
            twoChunkM4a(listOf(2L, 6L), payload, listOf(0L, 2L)),
        )
        val reorderedSwap = accepted(
            twoChunkM4a(listOf(2L, 6L), payload, listOf(2L, 0L)),
        )
        assertEquals(reordered.mediaPayloadSha256, reorderedSwap.mediaPayloadSha256)
        assertNotEquals(
            reordered.samples.samplesSha256,
            reorderedSwap.samples.samplesSha256,
        )
    }

    @Test fun rejectsDuplicateSampleReference() {
        val payload = ByteArray(8) { it.toByte() }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("DUPLICATE_SAMPLE_REFERENCE"),
            inspect(twoChunkM4a(listOf(4L, 4L), payload, listOf(0L, 0L))),
        )
    }

    @Test fun rejectsNonexistentStscDescription() {
        val bytes = m4aWithTracks(listOf(4L, 4L)) { offset ->
            listOf(
                trak(
                    offset,
                    listOf(4L, 4L),
                    compact = false,
                    compositionOffsets = false,
                    withEditList = false,
                    stscBytes = stsc(2, description = 2),
                ),
            )
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused(
                "SAMPLE_DESCRIPTION_INDEX_OUT_OF_RANGE",
            ),
            inspect(bytes),
        )
    }

    @Test fun rejectsInconsistentPerChunkReferences() {
        // Two chunk offsets for a single declared sample.
        val extraChunk = m4aWithTracks(listOf(4L), ByteArray(8)) { offset ->
            listOf(
                trak(
                    offset,
                    listOf(4L),
                    compact = false,
                    compositionOffsets = false,
                    withEditList = false,
                    chunkOffsets = listOf(offset, offset + 4L),
                    stscBytes = stsc(1),
                ),
            )
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("INVALID_STSC"),
            inspect(extraChunk),
        )

        // The first stsc range must start at chunk 1.
        val startsLate = m4aWithTracks(listOf(4L)) { offset ->
            listOf(
                trak(
                    offset,
                    listOf(4L),
                    compact = false,
                    compositionOffsets = false,
                    withEditList = false,
                    chunkOffsets = listOf(offset, offset + 4L),
                    stscBytes = box("stsc", zeros(4) + u32(1) + u32(2) + u32(1) + u32(1)),
                ),
            )
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("INVALID_STSC"),
            inspect(startsLate),
        )
    }

    @Test fun detectsModifiedCodecConfiguration() {
        val firstConfig = box("esds", byteArrayOf(1, 2, 3, 4))
        val secondConfig = box("esds", byteArrayOf(1, 2, 3, 5))
        val baseline = accepted(
            m4aWithTracks(listOf(4L, 4L)) { offset ->
                listOf(
                    trak(
                        offset,
                        listOf(4L, 4L),
                        compact = false,
                        compositionOffsets = false,
                        withEditList = false,
                        stsdBytes = stsd("mp4a", children = firstConfig),
                    ),
                )
            },
        )
        val modified = accepted(
            m4aWithTracks(listOf(4L, 4L)) { offset ->
                listOf(
                    trak(
                        offset,
                        listOf(4L, 4L),
                        compact = false,
                        compositionOffsets = false,
                        withEditList = false,
                        stsdBytes = stsd("mp4a", children = secondConfig),
                    ),
                )
            },
        )
        assertEquals(baseline.samples.samplesSha256, modified.samples.samplesSha256)
        assertEquals(baseline.samples.timelineSha256, modified.samples.timelineSha256)
        assertNotEquals(
            baseline.samples.codecConfigSha256,
            modified.samples.codecConfigSha256,
        )
        assertNotEquals(
            baseline.samples.fingerprintSha256,
            modified.samples.fingerprintSha256,
        )
    }

    @Test fun acceptsVersionOneAndTwoSampleEntriesWithDistinctConfigEvidence() {
        fun evidenceFor(version: Int) = accepted(
            m4aWithTracks(listOf(4L, 4L)) { offset ->
                listOf(
                    trak(
                        offset,
                        listOf(4L, 4L),
                        compact = false,
                        compositionOffsets = false,
                        withEditList = false,
                        stsdBytes = stsd("mp4a", sampleEntryVersion = version),
                    ),
                )
            },
        )
        val v0 = evidenceFor(0)
        val v1 = evidenceFor(1)
        val v2 = evidenceFor(2)
        assertEquals(v0.samples.samplesSha256, v1.samples.samplesSha256)
        assertEquals(v0.samples.samplesSha256, v2.samples.samplesSha256)
        assertNotEquals(
            v0.samples.codecConfigSha256,
            v1.samples.codecConfigSha256,
        )
        assertNotEquals(
            v0.samples.codecConfigSha256,
            v2.samples.codecConfigSha256,
        )
    }

    @Test fun detectsModifiedDecodeTiming() {
        val baseline = accepted(
            m4aWithTracks(listOf(4L, 4L)) { offset ->
                listOf(
                    trak(
                        offset,
                        listOf(4L, 4L),
                        compact = false,
                        compositionOffsets = false,
                        withEditList = false,
                        sttsBytes = stts(2, 1_024),
                    ),
                )
            },
        )
        val modified = accepted(
            m4aWithTracks(listOf(4L, 4L)) { offset ->
                listOf(
                    trak(
                        offset,
                        listOf(4L, 4L),
                        compact = false,
                        compositionOffsets = false,
                        withEditList = false,
                        sttsBytes = stts(2, 512),
                    ),
                )
            },
        )
        assertEquals(baseline.samples.samplesSha256, modified.samples.samplesSha256)
        assertEquals(
            baseline.samples.codecConfigSha256,
            modified.samples.codecConfigSha256,
        )
        assertNotEquals(baseline.samples.timelineSha256, modified.samples.timelineSha256)
        assertNotEquals(
            baseline.samples.fingerprintSha256,
            modified.samples.fingerprintSha256,
        )
        assertEquals(2L * 1_024L, baseline.samples.tracks.single().decodeDuration)
        assertEquals(2L * 512L, modified.samples.tracks.single().decodeDuration)
    }

    @Test fun rejectsExternalDataReference() {
        val bytes = m4aWithTracks(listOf(4L)) { offset ->
            listOf(
                trak(
                    offset,
                    listOf(4L),
                    compact = false,
                    compositionOffsets = false,
                    withEditList = false,
                    drefBytes = dinf(selfContained = false),
                ),
            )
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("EXTERNAL_DATA_REFERENCE"),
            inspect(bytes),
        )
    }

    @Test fun rejectsProtectedSampleEntryChild() {
        val bytes = m4aWithTracks(listOf(4L)) { offset ->
            listOf(
                trak(
                    offset,
                    listOf(4L),
                    compact = false,
                    compositionOffsets = false,
                    withEditList = false,
                    stsdBytes = stsd("mp4a", children = box("sinf", zeros(8))),
                ),
            )
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("ENCRYPTED_TRACK"),
            inspect(bytes),
        )
    }

    @Test fun rejectsVideoTrackPresent() {
        val sizes = listOf(4L, 4L, 4L)
        val bytes = m4aWithTracks(sizes) { offset ->
            listOf(
                trak(
                    offset, sizes, compact = false, compositionOffsets = false,
                    withEditList = false, trackId = 1L,
                ),
                trak(
                    offset, sizes, compact = false, compositionOffsets = false,
                    withEditList = false, handler = "vide", trackId = 2L,
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
        val withMvex = audioOnlyM4a().let { bytes ->
            // Rebuild the moov with an mvex child.
            val mvex = box("mvex", zeros(8))
            val ftypSize = ByteBuffer.wrap(bytes, 0, 4).int
            val moovSize = ByteBuffer.wrap(bytes, ftypSize, 4).int
            val payload = bytes.copyOfRange(ftypSize + 8, ftypSize + moovSize)
            val nextMoov = box("moov", payload + mvex)
            box("ftyp", bytes.copyOfRange(8, ftypSize)) + nextMoov +
                bytes.copyOfRange(ftypSize + moovSize, bytes.size)
        }
        assertEquals(
            MetadataM4aAudioOnlyInspector.Result.Refused("FRAGMENTED_MP4_NOT_CERTIFIED"),
            inspect(withMvex),
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
        assertEquals(12L, evidence.samples.sampleBytes)
    }

    @Test fun cancellationCallbackAbortsInsteadOfRefusing() {
        val file = File.createTempFile("michi-m4a-cancel-", ".m4a")
        try {
            file.writeBytes(audioOnlyM4a())
            var checks = 0
            try {
                RandomAccessFile(file, "r").use { handle ->
                    MetadataM4aAudioOnlyInspector.inspect(handle.channel) {
                        if (++checks >= 3) throw kotlinx.coroutines.CancellationException(
                            "cancel-inspection",
                        )
                    }
                }
                org.junit.Assert.fail("inspection must observe cancellation")
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                assertEquals("cancel-inspection", cancel.message)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun realAacFixtureMatchesDeclaredSamples() {
        val bytes = fixture().readBytes()
        val evidence = accepted(bytes)
        assertEquals(1, evidence.audioTracks)
        assertEquals("mp4a", evidence.codec)
        assertEquals(17L, evidence.samples.sampleCount)
        assertEquals(4_612L, evidence.samples.sampleBytes)
        assertEquals(4_612L, evidence.mediaPayloadBytes)
        val track = evidence.samples.tracks.single()
        assertEquals(1L, track.trackId)
        assertEquals("mp4a", track.codec)
        assertEquals(1, track.dataReferenceIndex)
        assertEquals(1, track.channelCount)
        assertEquals(16, track.sampleSize)
        assertEquals(44_100, track.sampleRate)
        assertEquals(44_100L, track.timeScale)
        assertEquals(17L, track.sampleCount)
        assertEquals(4_612L, track.sampleBytes)
        assertEquals(1, track.edits.size)
        assertEquals(64, track.timelineSha256.length)
        assertEquals(64, track.samplesSha256.length)
        assertEquals(64, track.codecConfigSha256.length)
    }

    @Test fun realAacFixtureMatchesFfprobePackets() {
        val available = runCatching {
            ProcessBuilder("ffprobe", "-version")
                .redirectErrorStream(true)
                .start()
                .apply { waitFor(10, TimeUnit.SECONDS) }
                .exitValue() == 0
        }.getOrDefault(false)
        assumeTrue("ffprobe is not available", available)
        val file = fixture()
        val process = ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "packet=size,pos",
            "-of", "csv=p=0",
            file.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("ffprobe did not finish", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("ffprobe failed: $output", 0, process.exitValue())
        val packets = output.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = line.split(',')
                fields[0].trim().toInt() to fields[1].trim().toLong()
            }
            .toList()
        assertEquals(17, packets.size)
        assertEquals(4_612L, packets.sumOf { it.first.toLong() })

        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
        feedText(digest, "M4A-SAMPLES-V1")
        feedLong(digest, 1L)
        feedText(digest, "track")
        feedLong(digest, 1L)
        feedLong(digest, packets.size.toLong())
        packets.forEachIndexed { index, (size, position) ->
            feedLong(digest, index.toLong())
            feedLong(digest, size.toLong())
            digest.update(bytes, position.toInt(), size)
        }
        val expected = digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        val evidence = accepted(bytes)
        assertEquals(expected, evidence.samples.samplesSha256)
        assertEquals(
            packets.sumOf { it.first.toLong() },
            evidence.samples.sampleBytes,
        )
    }
}
