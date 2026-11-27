package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataId3NumberDateStageTest {
    private val audio = ByteArray(2048) { ((it + 11) % 239).toByte() }

    private fun sync(n: Int) = byteArrayOf(
        ((n ushr 21) and 127).toByte(), ((n ushr 14) and 127).toByte(),
        ((n ushr 7) and 127).toByte(), (n and 127).toByte(),
    )

    private fun frame(id: String, value: String, version: Int): ByteArray {
        val payload = byteArrayOf(if (version == 3) 1 else 3) +
            value.toByteArray(if (version == 3) StandardCharsets.UTF_16
                else StandardCharsets.UTF_8)
        val head = ByteArrayOutputStream()
        head.write(id.toByteArray(StandardCharsets.US_ASCII))
        head.write(if (version == 3) byteArrayOf(
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte(),
        ) else sync(payload.size))
        head.write(0); head.write(0); head.write(payload)
        return head.toByteArray()
    }

    private fun sample(
        version: Int,
        vararg frames: Pair<String, String>,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { body.write(frame(it.first, it.second, version)) }
        body.write(frame("TIT2", "Keep Title", version))
        body.write(frame("TXXX", "KEEP PRIVATE", version))
        val content = body.toByteArray()
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(StandardCharsets.US_ASCII))
            write(version); write(0); write(0); write(sync(content.size))
            write(content); write(audio)
        }.toByteArray()
    }

    private fun review(
        known: Map<MetadataField, List<String>>,
        vararg changes: MetadataChange,
    ): MetadataReview.Ready {
        val snapshot = MetadataSnapshot(
            MetadataTarget("", "n", "content://fixture/n", "cold"),
            true, known, verifiedFields = known.keys,
        )
        return MetadataEditPreview.review(snapshot, changes.toList())
            as MetadataReview.Ready
    }

    private fun hasBytes(bytes: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > bytes.size) return false
        return (0..bytes.size - needle.size).any { start ->
            needle.indices.all { bytes[start + it] == needle[it] }
        }
    }

    private fun observe(file: File, field: MetadataField): List<String> {
        val report = java.nio.channels.FileChannel.open(
            file.toPath(), java.nio.file.StandardOpenOption.READ,
        ).use(MetadataColdFieldParser::observe)
        return (report.observation(field) as MetadataFieldObservation.Present).values
    }

    private fun inTemp(action: (File, File) -> Unit) {
        val dir = Files.createTempDirectory("me-id3-pairs").toFile()
        try { action(File(dir, "cache.mp3"), File(dir, "stage.mp3")) }
        finally { dir.deleteRecursively() }
    }

    @Test fun v24TotalOnlyEditPreservesExistingNumberAndAudio() = inTemp { source, stage ->
        source.writeBytes(sample(4, "TRCK" to "3"))
        val result = MetadataId3StageRewriter.stage(source, stage, review(
            mapOf(
                MetadataField.TRACK_NUMBER to listOf("3"),
                MetadataField.TRACK_TOTAL to emptyList(),
            ),
            MetadataChange.Replace(MetadataField.TRACK_TOTAL, listOf("12")),
        ), source.length())
        assertEquals(MetadataAudioContainer.ID3_V24, result.format)
        assertEquals(listOf("3"), observe(stage, MetadataField.TRACK_NUMBER))
        assertEquals(listOf("12"), observe(stage, MetadataField.TRACK_TOTAL))
        assertTrue(hasBytes(stage.readBytes(), "KEEP PRIVATE".toByteArray()))
        assertArrayEquals(audio, stage.readBytes().takeLast(audio.size).toByteArray())
    }

    @Test fun v23CombinedNumberAndTotalComposeOneFrame() = inTemp { source, stage ->
        source.writeBytes(sample(3, "TRCK" to "3"))
        MetadataId3StageRewriter.stage(source, stage, review(
            mapOf(
                MetadataField.TRACK_NUMBER to listOf("3"),
                MetadataField.TRACK_TOTAL to emptyList(),
            ),
            MetadataChange.Replace(MetadataField.TRACK_TOTAL, listOf("9")),
            MetadataChange.Replace(MetadataField.TRACK_NUMBER, listOf("4")),
        ), source.length())
        assertEquals(listOf("4"), observe(stage, MetadataField.TRACK_NUMBER))
        assertEquals(listOf("9"), observe(stage, MetadataField.TRACK_TOTAL))
        assertArrayEquals(audio, stage.readBytes().takeLast(audio.size).toByteArray())
    }

    @Test fun clearingTotalKeepsNumber() = inTemp { source, stage ->
        source.writeBytes(sample(4, "TRCK" to "3/12"))
        MetadataId3StageRewriter.stage(source, stage, review(
            mapOf(
                MetadataField.TRACK_NUMBER to listOf("3"),
                MetadataField.TRACK_TOTAL to listOf("12"),
            ),
            MetadataChange.Clear(MetadataField.TRACK_TOTAL),
        ), source.length())
        assertEquals(listOf("3"), observe(stage, MetadataField.TRACK_NUMBER))
        assertEquals(MetadataFieldObservation.VerifiedAbsent,
            java.nio.channels.FileChannel.open(stage.toPath(),
                java.nio.file.StandardOpenOption.READ)
                .use(MetadataColdFieldParser::observe)
                .observation(MetadataField.TRACK_TOTAL))
    }

    @Test fun clearingNumberWithTotalRemainingIsRejected() = inTemp { source, stage ->
        source.writeBytes(sample(4, "TRCK" to "3/12"))
        try {
            MetadataId3StageRewriter.stage(source, stage, review(
                mapOf(
                    MetadataField.TRACK_NUMBER to listOf("3"),
                    MetadataField.TRACK_TOTAL to listOf("12"),
                ),
                MetadataChange.Clear(MetadataField.TRACK_NUMBER),
            ), source.length())
            throw AssertionError()
        } catch (e: MetadataId3StageRewriter.Rejected) {
            assertEquals(MetadataId3StageRewriter.Code.INVALID_NUMBER_PAIR, e.code)
        }
        assertFalse(stage.exists())
    }

    @Test fun malformedExistingPairIsAmbiguous() = inTemp { source, stage ->
        source.writeBytes(sample(4, "TRCK" to "not-a-number"))
        try {
            MetadataId3StageRewriter.stage(source, stage, review(
                mapOf(
                    MetadataField.TRACK_NUMBER to listOf("3"),
                    MetadataField.TRACK_TOTAL to emptyList(),
                ),
                MetadataChange.Replace(MetadataField.TRACK_NUMBER, listOf("4")),
            ), source.length())
            throw AssertionError()
        } catch (e: MetadataId3StageRewriter.Rejected) {
            assertEquals(MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS, e.code)
        }
    }

    @Test fun duplicatePairFrameIsAmbiguous() = inTemp { source, stage ->
        source.writeBytes(sample(4, "TRCK" to "3", "TRCK" to "3"))
        try {
            MetadataId3StageRewriter.stage(source, stage, review(
                mapOf(
                    MetadataField.TRACK_NUMBER to listOf("3"),
                    MetadataField.TRACK_TOTAL to emptyList(),
                ),
                MetadataChange.Replace(MetadataField.TRACK_NUMBER, listOf("4")),
            ), source.length())
            throw AssertionError()
        } catch (e: MetadataId3StageRewriter.Rejected) {
            assertEquals(MetadataId3StageRewriter.Code.AMBIGUOUS_OR_STALE_TAGS, e.code)
        }
    }

    @Test fun v24ReleaseDateUsesTdrc() = inTemp { source, stage ->
        source.writeBytes(sample(4, "TDRC" to "2024"))
        MetadataId3StageRewriter.stage(source, stage, review(
            mapOf(MetadataField.RELEASE_DATE to listOf("2024")),
            MetadataChange.Replace(MetadataField.RELEASE_DATE, listOf("2024-05-03")),
        ), source.length())
        assertEquals(listOf("2024-05-03"), observe(stage, MetadataField.RELEASE_DATE))
        assertTrue(hasBytes(stage.readBytes(), "TDRC".toByteArray()))
        assertArrayEquals(audio, stage.readBytes().takeLast(audio.size).toByteArray())
    }

    @Test fun v23ReleaseDateUsesTyerAndRejectsExtendedDate() = inTemp { source, stage ->
        source.writeBytes(sample(3, "TYER" to "2024"))
        MetadataId3StageRewriter.stage(source, stage, review(
            mapOf(MetadataField.RELEASE_DATE to listOf("2024")),
            MetadataChange.Replace(MetadataField.RELEASE_DATE, listOf("2025")),
        ), source.length())
        assertEquals(listOf("2025"), observe(stage, MetadataField.RELEASE_DATE))
        assertTrue(hasBytes(stage.readBytes(), "TYER".toByteArray()))

        val rejected = File(stage.parentFile, "rejected.mp3")
        source.writeBytes(sample(3, "TYER" to "2025"))
        try {
            MetadataId3StageRewriter.stage(source, rejected, review(
                mapOf(MetadataField.RELEASE_DATE to listOf("2025")),
                MetadataChange.Replace(
                    MetadataField.RELEASE_DATE, listOf("2025-05-03"),
                ),
            ), source.length())
            throw AssertionError()
        } catch (e: MetadataId3StageRewriter.Rejected) {
            assertEquals(MetadataId3StageRewriter.Code.INVALID_DATE, e.code)
        }
        assertFalse(rejected.exists())
    }

    @Test fun discPairUsesTposAndPreservesUnknownFrames() = inTemp { source, stage ->
        source.writeBytes(sample(4, "TPOS" to "1/2"))
        MetadataId3StageRewriter.stage(source, stage, review(
            mapOf(
                MetadataField.DISC_NUMBER to listOf("1"),
                MetadataField.DISC_TOTAL to listOf("2"),
            ),
            MetadataChange.Replace(MetadataField.DISC_TOTAL, listOf("3")),
        ), source.length())
        assertEquals(listOf("1"), observe(stage, MetadataField.DISC_NUMBER))
        assertEquals(listOf("3"), observe(stage, MetadataField.DISC_TOTAL))
        assertTrue(hasBytes(stage.readBytes(), "KEEP PRIVATE".toByteArray()))
        assertArrayEquals(audio, stage.readBytes().takeLast(audio.size).toByteArray())
    }
}
