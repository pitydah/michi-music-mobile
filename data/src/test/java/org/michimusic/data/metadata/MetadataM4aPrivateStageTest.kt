package org.michimusic.data.metadata

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataArtworkAdditionPreview
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataArtworkReplacementPreview
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectiveAssetStage
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

/**
 * MF01: the private M4A stage must prove that every save references the same
 * actual sample bytes, codec configuration, timeline, edit list and track
 * identity. The fixture is a real AAC/M4A file whose `moov` precedes `mdat`, so
 * cover-art surgery relocates `moov` and every `stco` entry before the gate can
 * pass. Scalar text goes through the pinned JAudiotagger engine instead.
 */
class MetadataM4aPrivateStageTest {
    private fun fixture(): File {
        val candidates = listOf(
            File("src/androidTest/assets/me14-audio-only.m4a"),
            File("data/src/androidTest/assets/me14-audio-only.m4a"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("the real AAC fixture is not available")
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }

    private fun evidence(file: File): MetadataM4aAudioOnlyInspector.Evidence {
        val result = FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use(MetadataM4aAudioOnlyInspector::inspect)
        assertTrue("fixture must be audio-only: $result",
            result is MetadataM4aAudioOnlyInspector.Result.AudioOnly)
        return (result as MetadataM4aAudioOnlyInspector.Result.AudioOnly).evidence
    }

    private fun snapshot(file: File): MetadataSnapshot {
        val report = FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use(MetadataColdFieldParser::observe)
        val known = report.observations.mapNotNull { (field, observation) ->
            if (observation is MetadataFieldObservation.Present) {
                field to observation.values
            } else {
                null
            }
        }.toMap()
        return MetadataSnapshot(
            target = MetadataTarget("", "track", "content://michi/test/1", "proof"),
            tagsVerified = true,
            known = known,
            verifiedFields = report.verifiedFields,
        )
    }

    private fun review(
        file: File,
        changes: List<MetadataChange>,
    ): MetadataReview.Ready {
        val result = MetadataEditPreview.review(snapshot(file), changes)
        assertTrue("review must be ready: $result", result is MetadataReview.Ready)
        return result as MetadataReview.Ready
    }

    private fun observed(file: File): MetadataArtworkLyricsProbe.Snapshot {
        val result = MetadataPrivateBundleStage.observeFile(file)
        assertEquals(MetadataArtworkLyricsProbe.Format.MP4, result.format)
        return result
    }

    private fun png(mark: Int): ByteArray =
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        ) + ByteArray(8) { mark.toByte() } + ByteArray(8) { (mark + 1).toByte() }

    private fun <T> withSource(work: (File, File) -> T): T {
        val directory = Files.createTempDirectory("mf01-m4a-stage-").toFile()
        val source = File(directory, "source.m4a")
        try {
            fixture().copyTo(source, overwrite = true)
            return work(source, directory)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private fun stage(
        source: File,
        destination: File,
        review: MetadataReview.Ready?,
        edits: List<MetadataSelectiveAssetStage.Edit> = emptyList(),
        resources: MetadataArtworkLyricsProbe.Snapshot? = null,
    ): MetadataM4aPrivateStage.Receipt {
        assertFalse(destination.exists())
        return MetadataM4aPrivateStage.stage(
            source,
            destination,
            MetadataM4aPrivateStage.Request(
                sourceSha256 = sha(source),
                expectedBytes = source.length(),
                review = review,
                resourceEdits = edits,
                resources = resources,
            ),
        )
    }

    private fun assertMoovAfterMdat(file: File) {
        val bytes = file.readBytes()
        val boxes = ArrayList<Pair<String, Int>>()
        var at = 0
        while (at + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            assertTrue("invalid top-level size for $type", size >= 8 && at + size <= bytes.size)
            boxes += type to at
            at += size
        }
        val mdat = boxes.firstOrNull { it.first == "mdat" }?.second
            ?: error("candidate has no mdat")
        val moov = boxes.firstOrNull { it.first == "moov" }?.second
            ?: error("candidate has no moov")
        assertTrue("moov must be relocated after mdat", moov > mdat)
    }

    @Test
    fun textSavePreservesActualSampleBytes() = withSource { source, directory ->
        val before = evidence(source)
        val sourceSha = sha(source)
        val destination = File(directory, "text.m4a")
        val receipt = stage(
            source,
            destination,
            review(
                source,
                listOf(MetadataChange.Replace(
                    MetadataField.TITLE,
                    listOf("Michi Renombrada"),
                )),
            ),
        )
        val after = evidence(destination)
        assertTrue(
            "text staging must preserve real sample evidence",
            MetadataM4aAudioOnlyInspector.equivalent(before, after),
        )
        assertEquals(before.samples, receipt.samples)
        assertEquals(before.mediaPayloadSha256, receipt.audioPayloadSha256)
        assertEquals(sourceSha, sha(source))
        assertEquals(destination.length(), receipt.candidateBytes)
        val report = FileChannel.open(
            destination.toPath(),
            StandardOpenOption.READ,
        ).use(MetadataColdFieldParser::observe)
        assertEquals(
            listOf("Michi Renombrada"),
            (report.observation(MetadataField.TITLE) as MetadataFieldObservation.Present)
                .values,
        )
    }

    @Test
    fun coverAddRelocatesMoovAndPreservesActualSamples() = withSource { source, directory ->
        val before = evidence(source)
        val snapshot = observed(source)
        val proposal = MetadataArtworkAdditionPreview.review(
            snapshot,
            png(1),
            "image/png",
        ) as MetadataArtworkAdditionPreview.Result.Ready
        val destination = File(directory, "cover-add.m4a")
        val receipt = stage(
            source,
            destination,
            review = null,
            edits = listOf(
                MetadataSelectiveAssetStage.Edit.PictureAdd(proposal.proposal),
            ),
            resources = snapshot,
        )
        val after = evidence(destination)
        assertTrue(
            "cover add must preserve real sample evidence",
            MetadataM4aAudioOnlyInspector.equivalent(before, after),
        )
        assertEquals(before.samples, receipt.samples)
        assertMoovAfterMdat(destination)
        val candidate = observed(destination)
        assertEquals(1, candidate.artworks.size)
        assertArrayEquals(png(1), candidate.artworks.single().image)
    }

    @Test
    fun coverReplacePreservesActualSamples() = withSource { source, directory ->
        val withCover = File(directory, "with-cover.m4a")
        val firstSnapshot = observed(source)
        val first = MetadataArtworkAdditionPreview.review(
            firstSnapshot,
            png(2),
            "image/png",
        ) as MetadataArtworkAdditionPreview.Result.Ready
        stage(
            source,
            withCover,
            review = null,
            edits = listOf(MetadataSelectiveAssetStage.Edit.PictureAdd(first.proposal)),
            resources = firstSnapshot,
        )
        val before = evidence(withCover)
        val coverSnapshot = observed(withCover)
        val replacement = MetadataArtworkReplacementPreview.review(
            coverSnapshot,
            0,
            png(3),
            "image/png",
        ) as MetadataArtworkReplacementPreview.Result.Ready
        val destination = File(directory, "cover-replace.m4a")
        val receipt = stage(
            withCover,
            destination,
            review = null,
            edits = listOf(MetadataSelectiveAssetStage.Edit.Picture(replacement.proposal)),
            resources = coverSnapshot,
        )
        val after = evidence(destination)
        assertTrue(
            "cover replace must preserve real sample evidence",
            MetadataM4aAudioOnlyInspector.equivalent(before, after),
        )
        assertEquals(before.samples, receipt.samples)
        assertMoovAfterMdat(destination)
        val candidate = observed(destination)
        assertEquals(1, candidate.artworks.size)
        assertArrayEquals(png(3), candidate.artworks.single().image)
    }

    @Test
    fun coverRemovePreservesActualSamples() = withSource { source, directory ->
        val withCover = File(directory, "with-cover.m4a")
        val addedSnapshot = observed(source)
        val added = MetadataArtworkAdditionPreview.review(
            addedSnapshot,
            png(4),
            "image/png",
        ) as MetadataArtworkAdditionPreview.Result.Ready
        stage(
            source,
            withCover,
            review = null,
            edits = listOf(MetadataSelectiveAssetStage.Edit.PictureAdd(added.proposal)),
            resources = addedSnapshot,
        )
        val before = evidence(withCover)
        val coverSnapshot = observed(withCover)
        val destination = File(directory, "cover-remove.m4a")
        val receipt = stage(
            withCover,
            destination,
            review = null,
            edits = listOf(
                MetadataSelectiveAssetStage.Edit.PictureRemove(
                    0,
                    coverSnapshot.artworks.single().info.sha256,
                ),
            ),
            resources = coverSnapshot,
        )
        val after = evidence(destination)
        assertTrue(
            "cover remove must preserve real sample evidence",
            MetadataM4aAudioOnlyInspector.equivalent(before, after),
        )
        assertEquals(before.samples, receipt.samples)
        assertMoovAfterMdat(destination)
        assertTrue(observed(destination).artworks.isEmpty())
    }

    @Test
    fun combinedScalarAndResourceSavePreservesActualSamples() = withSource { source, directory ->
        val before = evidence(source)
        val snapshot = observed(source)
        val proposal = MetadataArtworkAdditionPreview.review(
            snapshot,
            png(5),
            "image/png",
        ) as MetadataArtworkAdditionPreview.Result.Ready
        val destination = File(directory, "combined.m4a")
        val receipt = stage(
            source,
            destination,
            review(
                source,
                listOf(
                    MetadataChange.Replace(
                        MetadataField.TITLE,
                        listOf("Michi Combinada"),
                    ),
                    MetadataChange.Replace(
                        MetadataField.ALBUM,
                        listOf("Michi Album Nuevo"),
                    ),
                ),
            ),
            edits = listOf(
                MetadataSelectiveAssetStage.Edit.PictureAdd(proposal.proposal),
            ),
            resources = snapshot,
        )
        val after = evidence(destination)
        assertTrue(
            "combined staging must preserve real sample evidence",
            MetadataM4aAudioOnlyInspector.equivalent(before, after),
        )
        assertEquals(before.samples, receipt.samples)
        assertMoovAfterMdat(destination)
        val report = FileChannel.open(
            destination.toPath(),
            StandardOpenOption.READ,
        ).use(MetadataColdFieldParser::observe)
        assertEquals(
            listOf("Michi Combinada"),
            (report.observation(MetadataField.TITLE) as MetadataFieldObservation.Present)
                .values,
        )
        assertEquals(
            listOf("Michi Album Nuevo"),
            (report.observation(MetadataField.ALBUM) as MetadataFieldObservation.Present)
                .values,
        )
        assertEquals(1, observed(destination).artworks.size)
    }
}
