package org.michimusic.data.metadata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSyntheticProviderEvidence as Evidence
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorColdRead

/**
 * ME-11S UX-04: a readable provider document with NO Room evidence must still
 * produce a physically verified snapshot. Room may inform the UI, never gate the
 * cold read, and the indexed reference stays explicitly separate.
 */
@RunWith(AndroidJUnit4::class)
class MetadataEditorColdReadInstrumentedTest {
    private lateinit var lab: MetadataSyntheticProviderTrial
    private lateinit var session: MetadataSyntheticProviderTrial.Session
    private lateinit var database: MichiDatabase
    private lateinit var repository: ExtendedTrackMetadataRepository

    private val taggedBytes: ByteArray = id3Title("Etiqueta física")
    private val candidateBytes: ByteArray = id3Title("Etiqueta candidata")

    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lab = MetadataSyntheticProviderTrial(instrumentation.context)
        session = lab.prepare(taggedBytes, candidateBytes)
        database = Room.inMemoryDatabaseBuilder(
            instrumentation.targetContext, MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ExtendedTrackMetadataRepository(
            trackDao = database.trackDao(),
            context = instrumentation.targetContext,
        )
    }

    @After fun cleanup() {
        database.close()
        val restored = lab.restore(session, explicitConfirmation = true)
        assertTrue(restored == Evidence.Restore.STILL_ORIGINAL ||
            restored == Evidence.Restore.SYNTHETIC_ORIGINAL_RESTORED)
        assertTrue(lab.discardSyntheticSession(session, explicitConfirmation = true))
    }

    @Test fun readableDocumentWithoutRoomEvidenceIsReadPhysically() = runBlocking {
        val result = repository.loadEditorColdRead(
            sourceId = "synthetic-source",
            trackId = "cold-read-track",
            filepath = session.uri.toString(),
            expectedSize = taggedBytes.size.toLong(),
        )
        assertTrue("Room must be absent in this fixture", result.indexed == null)
        val snapshot = when (result) {
            is MetadataEditorColdRead.Verified -> result.snapshot
            is MetadataEditorColdRead.Partial -> result.snapshot
            else -> error("expected a physically verified read, got $result")
        }
        assertTrue(snapshot.tagsVerified)
        assertEquals(
            listOf("Etiqueta física"),
            snapshot.known[MetadataField.TITLE],
        )
        assertTrue(snapshot.target.uri.startsWith("content://"))
    }

    /**
     * Minimal ID3v2.3 tag with a single TIT2 frame (Latin-1 text, encoding 0)
     * plus silence bytes. The parser's observed fields legitimately make this a
     * Partial read: the point is that the title came from the document, not Room.
     */
    private fun id3Title(title: String): ByteArray {
        val text = byteArrayOf(0) + title.toByteArray(Charsets.ISO_8859_1)
        val frame = "TIT2".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(
                ((text.size shr 24) and 0xFF).toByte(),
                ((text.size shr 16) and 0xFF).toByte(),
                ((text.size shr 8) and 0xFF).toByte(),
                (text.size and 0xFF).toByte(),
                0, 0,
            ) + text
        val size = frame.size
        val header = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            3, 0, 0,
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte(),
        )
        return header + frame + ByteArray(1024) { (it % 251).toByte() }
    }
}
