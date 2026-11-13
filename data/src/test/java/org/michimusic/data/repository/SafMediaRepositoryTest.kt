package org.michimusic.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.TrackDao
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafMediaRepositoryTest {

    @Test
    fun createFolderWithoutPersistedWriteGrantNeverQueriesOrMutatesProvider() = runTest {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val trackDao = mockk<TrackDao>(relaxed = true)
        val grant = mockk<UriPermission>()
        val tree = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic",
        )
        every { context.contentResolver } returns resolver
        every { resolver.persistedUriPermissions } returns listOf(grant)
        every { grant.uri } returns tree
        every { grant.isReadPermission } returns true
        every { grant.isWritePermission } returns false
        val result = SafMediaRepository(context, trackDao).createRootSubfolder(tree, "Jazz")
        assertEquals(MusicFolderCreationResult.PermissionDenied, result)
        io.mockk.verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun invalidFolderNameFailsBeforeAccessingStorageProvider() = runTest {
        val repo = SafMediaRepository(mockk(), mockk())
        assertEquals(
            MusicFolderCreationResult.InvalidName,
            repo.createRootSubfolder(Uri.parse("content://example/tree/x"), "../escape"),
        )
    }

    @Test
    fun `takePersistablePermission catches SecurityException and returns false`() {
        val context = mockk<Context>()
        val contentResolver = mockk<ContentResolver>()
        val trackDao = mockk<TrackDao>(relaxed = true)

        every { context.contentResolver } returns contentResolver
        val uri = Uri.parse("content://nonexistent.authority/item")
        every {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } throws SecurityException("No permission")

        val repo = SafMediaRepository(context, trackDao)
        assertFalse(repo.takePersistablePermission(uri))
    }


    @Test
    fun indexTreeRecursesAndWritesOnlyProviderScopedRows() = runTest {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val treeDocumentId = DocumentsContract.getTreeDocumentId(tree)
        val rootChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocumentId)
        val folderChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "folder")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocumentId)
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val grant = mockk<UriPermission>()
        val trackDao = mockk<TrackDao>(relaxed = true)
        every { grant.uri } returns tree
        every { grant.isReadPermission } returns true
        every { context.contentResolver } returns resolver
        every { resolver.persistedUriPermissions } returns listOf(grant)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            when (firstArg<Uri>()) {
                document -> MatrixCursor(
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                ).apply { addRow(arrayOf("Mi música")) }
                rootChildren -> MatrixCursor(
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                ).apply {
                    addRow(arrayOf<Any?>("folder", "Rock", DocumentsContract.Document.MIME_TYPE_DIR, null))
                    addRow(arrayOf<Any?>("a", "Song.mp3", "audio/mpeg", 123L))
                }
                folderChildren -> MatrixCursor(
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                ).apply {
                    addRow(arrayOf<Any?>("b", "Other.flac", "audio/flac", 456L))
                }
                else -> null
            }
        }
        val rows = slot<List<org.michimusic.data.cache.CachedTrack>>()
        coEvery { trackDao.replaceForSourceWithEvidence(any(), capture(rows), any()) } returns Unit

        val result = SafMediaRepository(context, trackDao).indexTree(tree)

        assertTrue(result is TreeIndexResult.Success)
        result as TreeIndexResult.Success
        assertEquals(2, result.trackCount)
        assertEquals(customRootSourceId(tree), result.sourceId)
        assertEquals(listOf("Song", "Other"), rows.captured.map { it.title })
        assertTrue(rows.captured.all { it.sourceId == result.sourceId })
        assertTrue(rows.captured.map { it.id }.toSet().size == 2)
    }

    @Test
    fun indexTreeReportsRevokedGrantInsteadOfEmptySuccess() = runTest {
        val context = mockk<Context>()
        val contentResolver = mockk<ContentResolver>()
        val trackDao = mockk<TrackDao>(relaxed = true)
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        every { context.contentResolver } returns contentResolver
        every { contentResolver.persistedUriPermissions } returns emptyList()
        every {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } throws SecurityException("revoked")

        val result = SafMediaRepository(context, trackDao).indexTree(uri)

        assertEquals(TreeIndexResult.PermissionDenied, result)
    }
}
