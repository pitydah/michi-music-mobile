package org.michimusic.data.metadata

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLocalDraftStoreTest {
    private fun store(root: java.io.File) = FileMetadataLocalDraftStore(root) { 42L }

    private fun proposal(version: Long = 0L) = StoredMetadataProposal(
        draftId = "draft-private",
        sourceId = "saf-A",
        trackId = "track-1",
        trackUri = "content://doc/1",
        expectedSize = 1024L,
        evidenceToken = "evidence-1",
        version = version,
        operations = listOf(
            StoredMetadataOperation("GENRE", values = listOf("Jazz; Fusion", "Rock")),
            StoredMetadataOperation("COMMENT", clear = true),
        ),
        provisional = mapOf("MOOD" to listOf("Nocturno")),
        savedAtEpochMs = 0L,
    )

    @Test
    fun saveIsDurableAcrossStoreInstancesAndPreservesUnicodeOrder() = runBlocking {
        val root = Files.createTempDirectory("michi-draft-").toFile()
        val saved = store(root).save(null, proposal())
        assertTrue(saved is LocalDraftWrite.Saved)
        val found = store(root).find("saf-A", "track-1")!!
        assertEquals(1L, found.version)
        assertEquals(listOf("Jazz; Fusion", "Rock"), found.operations.first().values)
        assertTrue(found.operations[1].clear)
        assertEquals(listOf("Nocturno"), found.provisional["MOOD"])
        assertEquals("draft-private", found.draftId)
    }

    @Test
    fun compareAndSwapRejectsConcurrentVersionAndPreservesTheFirstDraft() = runBlocking {
        val root = Files.createTempDirectory("michi-draft-").toFile()
        val s = store(root)
        assertTrue(s.save(null, proposal()) is LocalDraftWrite.Saved)
        val second = s.save(1L, proposal(version = 0L).copy(operations = emptyList()))
        assertTrue(second is LocalDraftWrite.Saved)
        val conflict = s.save(1L, proposal().copy(operations = emptyList()))
        assertTrue(conflict is LocalDraftWrite.Rejected)
        assertEquals("VERSION_CONFLICT", (conflict as LocalDraftWrite.Rejected).code)
        assertEquals(2L, s.find("saf-A", "track-1")!!.version)
    }

    @Test
    fun deleteRequiresTheExactVersionAndRemovesThePrivateFile() = runBlocking {
        val root = Files.createTempDirectory("michi-draft-").toFile()
        val s = store(root)
        assertTrue(s.save(null, proposal()) is LocalDraftWrite.Saved)
        assertFalse(s.delete("draft-private", 2L))
        assertTrue(s.delete("draft-private", 1L))
        assertNull(s.find("saf-A", "track-1"))
    }

    @Test
    fun corruptFileIsNeverSilentlyReplacedOrPromoted() = runBlocking {
        val root = Files.createTempDirectory("michi-draft-").toFile()
        val s = store(root)
        assertTrue(s.save(null, proposal()) is LocalDraftWrite.Saved)
        root.listFiles { file -> file.name.endsWith(".json") }!!.single()
            .writeText("{not-json", Charsets.UTF_8)
        assertNull(s.find("saf-A", "track-1"))
        val result = s.save(null, proposal())
        assertEquals("CORRUPT_DRAFT", (result as LocalDraftWrite.Rejected).code)
    }

    @Test
    fun oversizedProposalIsRejectedWithoutWriting() = runBlocking {
        val root = Files.createTempDirectory("michi-draft-").toFile()
        val values = List(33) { "value-$it" }
        val result = store(root).save(null, proposal().copy(
            operations = listOf(StoredMetadataOperation("GENRE", values = values)),
        ))
        assertEquals("DRAFT_TOO_LARGE", (result as LocalDraftWrite.Rejected).code)
        assertNull(store(root).find("saf-A", "track-1"))
    }
    @Test
    fun changedUriOwnerOrEvidenceDoesNotMatchTheStoredDraft() {
        val draft = proposal()
        assertTrue(draft.matchesIdentity("saf-A", "track-1", "content://doc/1", 1024L, "evidence-1"))
        assertFalse(draft.matchesIdentity("saf-B", "track-1", "content://doc/1", 1024L, "evidence-1"))
        assertFalse(draft.matchesIdentity("saf-A", "track-1", "content://doc/2", 1024L, "evidence-1"))
        assertFalse(draft.matchesIdentity("saf-A", "track-1", "content://doc/1", 1025L, "evidence-1"))
        assertFalse(draft.matchesIdentity("saf-A", "track-1", "content://doc/1", 1024L, "evidence-2"))
    }

}
