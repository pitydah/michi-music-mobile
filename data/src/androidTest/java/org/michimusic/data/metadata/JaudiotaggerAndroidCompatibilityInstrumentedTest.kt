package org.michimusic.data.metadata

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jaudiotagger.audio.mp4.Mp4FileReader
import org.jaudiotagger.audio.mp4.Mp4FileWriter
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compatibility gate for the pinned mature MP4 engine.
 *
 * Loading these classes in a real API 36 APK catches Android linkage/dex
 * incompatibilities that a JVM-only compile cannot prove.
 */
@RunWith(AndroidJUnit4::class)
class JaudiotaggerAndroidCompatibilityInstrumentedTest {
    @Test
    fun mp4ReaderWriterAndTagLoadOnAndroid() {
        TagOptionSingleton.getInstance().isAndroid = true

        assertNotNull(Mp4FileReader())
        assertNotNull(Mp4FileWriter())
        assertNotNull(Mp4Tag())
    }

    @Test
    fun titleRewritePreservesUneditedTagsAndExactMdatPayload() {
        TagOptionSingleton.getInstance().isAndroid = true
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "jaudiotagger-${System.nanoTime()}.m4a")
        context.assets.open("me14-audio-only.m4a").use { input ->
            file.outputStream().use(input::copyTo)
        }
        try {
            val beforeBytes = file.readBytes()
            val beforeMdat = mdatPayloads(beforeBytes)
            assertTrue(beforeMdat.isNotEmpty())

            val reader = Mp4FileReader()
            val audio = reader.read(file)
            assertEquals("M4A Original", audio.tag.getFirst(FieldKey.TITLE))
            assertEquals("Michi Fixture", audio.tag.getFirst(FieldKey.ARTIST))
            assertEquals("Michi Test Album", audio.tag.getFirst(FieldKey.ALBUM))
            assertEquals("preserve-me", audio.tag.getFirst(FieldKey.COMMENT))

            audio.tag.setField(FieldKey.TITLE, "M4A Renombrada")
            Mp4FileWriter().write(audio)

            val reopened = reader.read(file)
            assertEquals("M4A Renombrada", reopened.tag.getFirst(FieldKey.TITLE))
            assertEquals("Michi Fixture", reopened.tag.getFirst(FieldKey.ARTIST))
            assertEquals("Michi Test Album", reopened.tag.getFirst(FieldKey.ALBUM))
            assertEquals("preserve-me", reopened.tag.getFirst(FieldKey.COMMENT))
            assertArrayEquals(beforeMdat, mdatPayloads(file.readBytes()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun privateStageAcceptsAudioOnlyAndRejectsVideoMp4() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "m4a-stage-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "source.m4a")
            context.assets.open("me14-audio-only.m4a").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val target = MetadataTarget("source", "track", "content://media/1", "token")
            val snapshot = source.inputStream().use {
                MetadataColdFieldParser.observe(it.channel).toSnapshot(target)
            }
            val review = MetadataEditPreview.review(
                snapshot,
                listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("M4A Stage"))),
            )
            assertTrue(review is MetadataReview.Ready)
            val destination = File(root, "candidate.m4a")
            val receipt = MetadataM4aPrivateStage.stage(
                source,
                destination,
                MetadataM4aPrivateStage.Request(
                    sourceSha256 = sha(source.readBytes()),
                    expectedBytes = source.length(),
                    review = review as MetadataReview.Ready,
                ),
            )
            assertEquals(destination.length(), receipt.candidateBytes)
            assertEquals(sha(destination.readBytes()), receipt.candidateSha256)
            assertArrayEquals(mdatPayloads(source.readBytes()), mdatPayloads(destination.readBytes()))

            val video = File(root, "video.mp4")
            context.assets.open("me14-audio-video.mp4").use { input ->
                video.outputStream().use(input::copyTo)
            }
            val videoResult = video.inputStream().use {
                MetadataM4aAudioOnlyInspector.inspect(it.channel)
            }
            assertTrue(videoResult is MetadataM4aAudioOnlyInspector.Result.Refused)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun mdatPayloads(bytes: ByteArray): ByteArray {
        val payloads = ArrayList<ByteArray>()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size32 = ByteBuffer.wrap(bytes, offset, 4)
                .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val headerBytes: Int
            val atomBytes: Long
            when (size32) {
                0L -> {
                    headerBytes = 8
                    atomBytes = (bytes.size - offset).toLong()
                }
                1L -> {
                    if (offset + 16 > bytes.size) error("Truncated extended MP4 atom")
                    headerBytes = 16
                    atomBytes = ByteBuffer.wrap(bytes, offset + 8, 8)
                        .order(ByteOrder.BIG_ENDIAN).long
                }
                else -> {
                    headerBytes = 8
                    atomBytes = size32
                }
            }
            require(atomBytes >= headerBytes && atomBytes <= bytes.size - offset) {
                "Invalid MP4 atom $type at $offset"
            }
            if (type == "mdat") {
                payloads += bytes.copyOfRange(
                    offset + headerBytes,
                    offset + atomBytes.toInt(),
                )
            }
            offset += atomBytes.toInt()
        }
        require(offset == bytes.size) { "Trailing bytes outside MP4 atoms" }
        return payloads.fold(ByteArray(0)) { all, payload -> all + payload }
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
}
