package org.michimusic.data.metadata

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * ME-08 TEST APK ONLY. This provider owns a strictly bounded private fixture namespace.
 * Its "w" mode deliberately DOES NOT truncate, modeling a legal Android provider.
 * It is never registered in the shipping application and has no access to user audio.
 */
class MetadataSyntheticDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "org.michimusic.data.metadata.me08.testing"
        private val allowedId = Regex("me08-fixture-[a-f0-9]{32}")
        private val rootColumns = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )
        private val documentColumns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }

    override fun onCreate(): Boolean = true

    private fun fixture(id: String): File {
        if (!allowedId.matches(id)) throw FileNotFoundException("NOT_A_SYNTHETIC_DOCUMENT")
        val dir = File(context!!.noBackupFilesDir, "me08-provider-test-only")
        if (!dir.isDirectory && !dir.mkdirs()) throw FileNotFoundException("TEST_DIRECTORY_UNAVAILABLE")
        return File(dir, "$id.bin")
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: rootColumns
        val values = mapOf<String, Any?>(
            DocumentsContract.Root.COLUMN_ROOT_ID to "me08-root",
            DocumentsContract.Root.COLUMN_DOCUMENT_ID to "me08-root",
            DocumentsContract.Root.COLUMN_TITLE to "Michi ME08 Synthetic Test Only",
            DocumentsContract.Root.COLUMN_FLAGS to 0,
            DocumentsContract.Root.COLUMN_MIME_TYPES to "application/octet-stream",
        )
        return MatrixCursor(columns).apply { addRow(columns.map { values[it] }) }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = fixture(documentId)
        if (!file.isFile) throw FileNotFoundException("TEST_DOCUMENT_NOT_CREATED")
        val columns = projection ?: documentColumns
        val values = mapOf<String, Any?>(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to "$documentId.bin",
            DocumentsContract.Document.COLUMN_SIZE to file.length(),
            DocumentsContract.Document.COLUMN_MIME_TYPE to "application/octet-stream",
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            DocumentsContract.Document.COLUMN_FLAGS to (DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE),
        )
        return MatrixCursor(columns).apply { addRow(columns.map { values[it] }) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?,
        sortOrder: String?): Cursor = MatrixCursor(projection ?: documentColumns)

    override fun openDocument(documentId: String, mode: String,
        signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fixture(documentId)
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            // A provider is allowed to preserve trailing bytes on "w".
            "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("UNSUPPORTED_TEST_MODE")
        }
        if (mode == "r" && !file.isFile) throw FileNotFoundException("MISSING_TEST_DOCUMENT")
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun deleteDocument(documentId: String) {
        val file = fixture(documentId)
        if (file.isFile && !file.delete()) throw FileNotFoundException("TEST_DELETE_FAILED")
    }
}
