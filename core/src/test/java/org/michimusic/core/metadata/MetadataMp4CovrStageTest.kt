package org.michimusic.core.metadata

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ME-21: private `covr` surgery must add, replace and remove cover art on
 * synthetic ISO base media files while every unselected image and the whole
 * `mdat` payload stay byte-exact, including when `moov` has to be relocated
 * from in front of `mdat` to the end of the file.
 */
class MetadataMp4CovrStageTest {
    @Test
    fun addsCoverArtWhenMoovIsTerminalAndKeepsMediaExact() {
        val media = mediaPayload()
        val source = ftyp() + mdat(media) + moov(mvhd())
        val image = png(21)
        val (result, candidate) = stage(
            source,
            observed = emptySnapshot(),
            edit = listOf(add(0, image)),
        )
        assertEquals(1, result.resources.artworks.size)
        assertEquals("image/png", result.resources.artworks.single().info.mime)
        assertEquals(sha256(image), result.resources.artworks.single().info.sha256)
        assertArrayEquals(image, result.resources.artworks.single().image)
        assertArrayEquals(media, mdatPayload(candidate))
        assertEquals(candidate.size.toLong(), result.candidateBytes)
        assertEquals(sha256(candidate), result.candidateSha256)
        val observed = MetadataArtworkLyricsProbe.inspect(candidate)
        assertTrue(
            "the production probe must accept the produced container: $observed",
            observed is MetadataArtworkLyricsProbe.Result.Ready,
        )
    }

    @Test
    fun replacesOneImageAndPreservesTheUntouchedOne() {
        val first = png(31)
        val second = png(32)
        val replacement = png(33)
        val media = mediaPayload()
        val source = ftyp() + mdat(media) + moov(
            mvhd(),
            udta(meta(hdlr(), ilst(covr(first, second)))),
        )
        val (result, candidate) = stage(
            source,
            observed = snapshotOf(first, second),
            edit = listOf(replace(0, first, replacement)),
        )
        assertEquals(2, result.resources.artworks.size)
        assertArrayEquals(replacement, result.resources.artworks[0].image)
        assertArrayEquals(second, result.resources.artworks[1].image)
        assertEquals(sha256(replacement), result.resources.artworks[0].info.sha256)
        assertEquals(sha256(second), result.resources.artworks[1].info.sha256)
        assertArrayEquals(media, mdatPayload(candidate))
    }

    @Test
    fun removesLastImageAndPreservesTheRemainingOne() {
        val first = png(41)
        val second = png(42)
        val media = mediaPayload()
        val source = ftyp() + mdat(media) + moov(
            mvhd(),
            udta(meta(hdlr(), ilst(covr(first, second)))),
        )
        val (result, candidate) = stage(
            source,
            observed = snapshotOf(first, second),
            edit = listOf(remove(1, second)),
        )
        assertEquals(1, result.resources.artworks.size)
        assertArrayEquals(first, result.resources.artworks.single().image)
        assertArrayEquals(media, mdatPayload(candidate))
    }

    @Test
    fun removesTheOnlyImageAndDropsCovr() {
        val only = png(51)
        val media = mediaPayload()
        val source = ftyp() + mdat(media) + moov(
            mvhd(),
            udta(meta(hdlr(), ilst(covr(only)))),
        )
        val (result, candidate) = stage(
            source,
            observed = snapshotOf(only),
            edit = listOf(remove(0, only)),
        )
        assertTrue(result.resources.artworks.isEmpty())
        assertArrayEquals(media, mdatPayload(candidate))
        val observed = MetadataArtworkLyricsProbe.inspect(candidate)
        assertTrue(
            "a container without `covr` must still be readable: $observed",
            observed is MetadataArtworkLyricsProbe.Result.Ready,
        )
    }

    @Test
    fun relocatesLeadingMoovAndPatchesChunkOffsets() {
        val media = mediaPayload()
        val header = ftyp()
        val chunkOffset = header.size + 0 + 8 // placeholder, replaced below
        val stub = moov(
            mvhd(),
            trak(stbl(stco(listOf(chunkOffset)))),
        )
        val realOffset = header.size + stub.size + 8
        val source = header + moov(
            mvhd(),
            trak(stbl(stco(listOf(realOffset)))),
        ) + mdat(media)
        val image = png(61)
        val (result, candidate) = stage(
            source,
            observed = emptySnapshot(),
            edit = listOf(add(0, image)),
        )
        assertArrayEquals(image, result.resources.artworks.single().image)
        assertArrayEquals(media, mdatPayload(candidate))
        val at = indexOfAscii(candidate, "stco")
        assertTrue("the relocated `moov` must still declare `stco`", at > 0)
        assertEquals(1, be32At(candidate, at + 8))
        assertEquals(header.size + 8, be32At(candidate, at + 12))
        val position = indexOfAscii(candidate, "mdat")
        assertEquals(header.size, position - 4)
    }

    @Test
    fun refusesWhenTheObservedSnapshotDoesNotMatchTheSource() {
        val image = png(71)
        val source = ftyp() + mdat(mediaPayload()) + moov(mvhd())
        expectRefusal(
            source,
            observed = snapshotOf(image),
            edit = add(0, image),
            expected = MetadataMp4CovrStage.Code.SOURCE_CHANGED,
        )
    }

    @Test
    fun refusesAddressedImageThatNoLongerMatches() {
        val image = png(81)
        val source = ftyp() + mdat(mediaPayload()) +
            moov(mvhd(), udta(meta(hdlr(), ilst(covr(image)))))
        expectRefusal(
            source,
            observed = snapshotOf(image),
            edit = replace(0, png(99), png(82)),
            expected = MetadataMp4CovrStage.Code.SOURCE_CHANGED,
        )
    }

    @Test
    fun refusesDescribedTextEdits() {
        val source = ftyp() + mdat(mediaPayload()) + moov(mvhd())
        expectRefusal(
            source,
            observed = emptySnapshot(),
            edit = MetadataSelectiveAssetStage.Edit.DescribedText(
                MetadataDescribedTextWorkbench.Change.Clear(
                    MetadataDescribedTextWorkbench.Identity(
                        kind = MetadataId3DescribedTagCodec.Kind.LYRICS,
                        language = "spa",
                        description = "descripción",
                    ),
                ),
            ),
            expected = MetadataMp4CovrStage.Code.UNSUPPORTED_EDIT,
        )
    }

    @Test
    fun addsTwoImagesSequentiallyFromThePreviouslyStagedFile() {
        val first = png(91)
        val second = png(92)
        val source = ftyp() + mdat(mediaPayload()) + moov(mvhd())
        val (firstResult, firstCandidate) = stage(
            source,
            observed = emptySnapshot(),
            edit = listOf(add(0, first)),
        )
        assertEquals(1, firstResult.resources.artworks.size)
        val (secondResult, secondCandidate) = stage(
            firstCandidate,
            observed = firstResult.resources,
            edit = listOf(add(1, second)),
        )
        assertEquals(2, secondResult.resources.artworks.size)
        assertArrayEquals(first, secondResult.resources.artworks[0].image)
        assertArrayEquals(second, secondResult.resources.artworks[1].image)
        assertArrayEquals(mediaPayload(), mdatPayload(secondCandidate))
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun stage(
        sourceBytes: ByteArray,
        observed: MetadataArtworkLyricsProbe.Snapshot?,
        edit: List<MetadataSelectiveAssetStage.Edit>,
    ): Pair<MetadataMp4CovrStage.Result, ByteArray> {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "michi-covr-${UUID.randomUUID()}",
        )
        assertTrue("could not create the staging directory", directory.mkdirs())
        try {
            val source = File(directory, "source.m4a")
            source.writeBytes(sourceBytes)
            val destination = File(directory, "candidate.m4a")
            val result = MetadataMp4CovrStage.stage(
                source = source,
                destination = destination,
                expectedBytes = sourceBytes.size.toLong(),
                expectedSourceSha256 = sha256(sourceBytes),
                observed = observed,
                edits = edit.toList(),
            )
            return result to destination.readBytes()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun expectRefusal(
        sourceBytes: ByteArray,
        observed: MetadataArtworkLyricsProbe.Snapshot?,
        edit: MetadataSelectiveAssetStage.Edit,
        expected: MetadataMp4CovrStage.Code,
    ) {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "michi-covr-${UUID.randomUUID()}",
        )
        assertTrue("could not create the staging directory", directory.mkdirs())
        try {
            val source = File(directory, "source.m4a")
            source.writeBytes(sourceBytes)
            val destination = File(directory, "candidate.m4a")
            try {
                MetadataMp4CovrStage.stage(
                    source = source,
                    destination = destination,
                    expectedBytes = sourceBytes.size.toLong(),
                    expectedSourceSha256 = sha256(sourceBytes),
                    observed = observed,
                    edits = listOf(edit),
                )
                fail("the staging must refuse with $expected")
            } catch (refused: MetadataMp4CovrStage.Refused) {
                assertEquals(expected, refused.code)
                assertTrue(
                    "a refused staging must leave no candidate behind",
                    !destination.exists(),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun add(
        ordinal: Int,
        image: ByteArray,
        mime: String = "image/png",
    ): MetadataSelectiveAssetStage.Edit.PictureAdd =
        MetadataSelectiveAssetStage.Edit.PictureAdd(
            MetadataArtworkAdditionPreview.Proposal(
                ordinal = ordinal,
                type = 3,
                description = "",
                proposedMime = mime,
                proposedSize = image.size,
                proposedSha256 = sha256(image),
                proposedBytes = image,
            ),
        )

    private fun replace(
        index: Int,
        before: ByteArray,
        after: ByteArray,
        mime: String = "image/png",
    ): MetadataSelectiveAssetStage.Edit.Picture =
        MetadataSelectiveAssetStage.Edit.Picture(
            MetadataArtworkReplacementPreview.Proposal(
                index = index,
                sourceSha256 = sha256(before),
                proposedSha256 = sha256(after),
                proposedMime = mime,
                proposedSize = after.size,
                proposedBytes = after,
            ),
        )

    private fun remove(ordinal: Int, image: ByteArray):
        MetadataSelectiveAssetStage.Edit.PictureRemove =
        MetadataSelectiveAssetStage.Edit.PictureRemove(ordinal, sha256(image))

    private fun emptySnapshot(): MetadataArtworkLyricsProbe.Snapshot =
        MetadataArtworkLyricsProbe.Snapshot(
            format = MetadataArtworkLyricsProbe.Format.MP4,
            artworks = emptyList(),
            texts = emptyList(),
        )

    private fun snapshotOf(vararg images: ByteArray):
        MetadataArtworkLyricsProbe.Snapshot =
        MetadataArtworkLyricsProbe.Snapshot(
            format = MetadataArtworkLyricsProbe.Format.MP4,
            artworks = images.mapIndexed { index, image ->
                MetadataArtworkLyricsProbe.Artwork(
                    ordinal = index,
                    info = MetadataArtworkEvidence.Picture(
                        format = MetadataArtworkEvidence.Format.MP4_COVR,
                        type = 3,
                        mime = "image/png",
                        bytes = image.size,
                        sha256 = sha256(image),
                        description = "",
                    ),
                    image = image,
                )
            },
            texts = emptyList(),
        )

    // ------------------------------------------------------------------
    // Synthetic ISO base media fixtures
    // ------------------------------------------------------------------

    private fun mediaPayload(): ByteArray =
        ByteArray(2048) { ((it * 37) % 251).toByte() }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        var payload = ByteArray(0)
        parts.forEach { payload += it }
        return be32(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun ftyp(): ByteArray = box(
        "ftyp",
        "M4A ".toByteArray(Charsets.ISO_8859_1),
        be32(0x200),
        "M4A ".toByteArray(Charsets.ISO_8859_1),
        "mp42".toByteArray(Charsets.ISO_8859_1),
        "isom".toByteArray(Charsets.ISO_8859_1),
    )

    private fun mvhd(): ByteArray = box("mvhd", ByteArray(100))

    private fun trak(vararg children: ByteArray): ByteArray =
        box("trak", *children)

    private fun stbl(vararg children: ByteArray): ByteArray = box("stbl", *children)

    private fun stco(offsets: List<Int>): ByteArray =
        box(
            "stco",
            ByteArray(4),
            be32(offsets.size),
            offsets.fold(ByteArray(0)) { acc, value -> acc + be32(value) },
        )

    private fun hdlr(): ByteArray = box(
        "hdlr",
        ByteArray(4),
        ByteArray(4),
        "mdir".toByteArray(Charsets.ISO_8859_1),
        "appl".toByteArray(Charsets.ISO_8859_1),
        ByteArray(9),
    )

    private fun meta(vararg children: ByteArray): ByteArray =
        box("meta", ByteArray(4), *children)

    private fun udta(vararg children: ByteArray): ByteArray =
        box("udta", *children)

    private fun ilst(vararg children: ByteArray): ByteArray =
        box("ilst", *children)

    private fun covr(vararg images: ByteArray): ByteArray =
        box("covr", *images.map { dataBox(it) }.toTypedArray())

    private fun dataBox(image: ByteArray, kind: Int = 14): ByteArray =
        box(
            "data",
            be32(kind),
            ByteArray(4),
            image,
        )

    private fun moov(vararg children: ByteArray): ByteArray =
        box("moov", *children)

    private fun mdat(payload: ByteArray): ByteArray = box("mdat", payload)

    private fun png(mark: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 80, 78, 71, 13, 10, 26, 10,
    ) +
        be32(13) + "IHDR".toByteArray(Charsets.ISO_8859_1) + be32(16) + be32(20) +
        byteArrayOf(8, 2, 0, 0, 0) + ByteArray(4) { mark.toByte() } +
        be32(0) + "IEND".toByteArray(Charsets.ISO_8859_1) + ByteArray(4) { mark.toByte() }

    private fun mdatPayload(bytes: ByteArray): ByteArray {
        var at = 0
        while (at + 8 <= bytes.size) {
            val size = be32At(bytes, at)
            if (size < 8) break
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            if (type == "mdat") return bytes.copyOfRange(at + 8, at + size)
            at += size
        }
        throw AssertionError("the produced container must keep a `mdat` atom")
    }

    private fun indexOfAscii(bytes: ByteArray, text: String): Int {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        outer@ for (index in 0..bytes.size - needle.size) {
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) continue@outer
            }
            return index
        }
        return -1
    }

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun be32At(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 255) shl 24) or
            ((bytes[at + 1].toInt() and 255) shl 16) or
            ((bytes[at + 2].toInt() and 255) shl 8) or
            (bytes[at + 3].toInt() and 255)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
