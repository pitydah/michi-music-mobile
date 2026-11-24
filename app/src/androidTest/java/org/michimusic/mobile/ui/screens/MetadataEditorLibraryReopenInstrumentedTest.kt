package org.michimusic.mobile.ui.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.mobile.MainActivity

/**
 * ME-13 T48: real Library -> album -> track menu -> editor -> Guardar flow on a
 * disposable MediaStore MP3 with the persistent application database.
 */
@RunWith(AndroidJUnit4::class)
class MetadataEditorLibraryReopenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var trackDao: TrackDao
    private lateinit var journalRoot: File
    private var created: Uri? = null
    private var trackId = ""
    private val sourceId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
    private lateinit var audioPayload: ByteArray
    private lateinit var originalBytes: ByteArray

    @Before fun setUp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, "android.permission.READ_MEDIA_AUDIO")
        }
        trackDao = GlobalContext.get().get<TrackDao>()
        runCatching {
            runBlocking {
                GlobalContext.get()
                    .get<org.michimusic.data.repository.LocalMediaRepository>()
                    .invalidateCache()
            }
        }
        journalRoot = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
        journalRoot.deleteRecursively()
        audioPayload = InstrumentationRegistry.getInstrumentation().context.assets
            .open("me13-silence.mp3").use { it.readBytes() }
        originalBytes = id3(
            title = "ME13 T48 Original", artist = "Artista T48",
            album = "Album ME13 T48", genre = "Rock", audio = audioPayload,
        )
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "me13-t48-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ME13T48")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        created = uri
        context.contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                it.write(originalBytes); it.flush(); it.fd.sync()
            }
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        trackId = "local_${uri.lastPathSegment!!.toLong()}"
    }

    @After fun tearDown() {
        runBlocking {
            trackDao.getTrackBySource(sourceId, trackId)?.let { trackDao.delete(it) }
            created?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            journalRoot.deleteRecursively()
        }
    }

    @Test fun libraryToEditorSaveAndReopenShowsTheCommittedTitle() {
        completeOnboardingIfPresent()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Biblioteca").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Biblioteca").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Opciones para ME13 T48 Original")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Opciones para ME13 T48 Original").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("track_menu_edit_metadata").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("track_menu_edit_metadata").performClick()
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("metadata_workbench").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("metadata_section_basics").assertIsDisplayed()
        compose.onNodeWithTag("metadata_section_credits").assertIsDisplayed()
        compose.onNodeWithTag("metadata_section_discography").assertIsDisplayed()
        compose.onNodeWithTag("metadata_section_art_lyrics").assertIsDisplayed()
        // Seed the exact cache row only after the editor has completed its cold
        // read, so the local MediaStore track keeps its bare source identity.
        runBlocking { seedVerifiedRow() }
        compose.onNodeWithTag("metadata_input_title_0")
            .performTextReplacement("ME13 T48 Borrador")
        hideKeyboard()
        compose.onNodeWithTag("metadata_revert_changes").assertIsEnabled()
        compose.onNodeWithTag("metadata_revert_changes").performClick()
        compose.onNodeWithTag("metadata_confirm_revert").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("metadata_input_title_0")
            .performTextReplacement("ME13 T48 Guardada")
        hideKeyboard()
        compose.onNodeWithTag("metadata_save_file").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Cambios guardados").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Archivo verificado; biblioteca pendiente")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "The exact CAS must publish the library, not only the file",
            compose.onAllNodesWithText("Cambios guardados").fetchSemanticsNodes().isNotEmpty(),
        )
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("ME13 T48 Guardada").fetchSemanticsNodes().isNotEmpty()
        }

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Biblioteca").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Biblioteca").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Opciones para ME13 T48 Guardada")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Opciones para ME13 T48 Guardada").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("track_menu_edit_metadata").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("track_menu_edit_metadata").performClick()
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("metadata_workbench").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("metadata_input_title_0")
            .assert(hasText("ME13 T48 Guardada"))
        val row = runBlocking { trackDao.getTrackBySource(sourceId, trackId)!! }
        assertEquals("ME13 T48 Guardada", row.title)
        assertEquals(0L, row.verifiedAt)
        assertEquals("", row.verifiedSha256)
        val after = context.contentResolver.openInputStream(requireNotNull(created))!!
            .use { it.readBytes() }
        assertTrue("audio must stay byte-identical",
            after.copyOfRange(after.size - audioPayload.size, after.size)
                .contentEquals(audioPayload))
        assertTrue("journal must be clear after an indexed save",
            journalRoot.listFiles().orEmpty().none {
                it.name.startsWith("journal-v1.bin") ||
                    it.name.startsWith("backup-") || it.name.startsWith("stage-") ||
                    it.name.startsWith("intent-") || it.name.startsWith("owner-") ||
                    it.name.startsWith("index-reconciliation-")
            })
    }

    private suspend fun seedVerifiedRow() {
        val repository = SyncedTrackRepository(trackDao = trackDao)
        repository.saveLibrary(listOf(TrackDto(
            id = trackId, title = "ME13 T48 Original", artist = "Artista T48",
            album = "Album ME13 T48", duration = 100, size = originalBytes.size.toLong(),
            format = "mp3", genre = "Rock",
        )), sourceId)
        repository.markDownloadedVerified(
            sourceId = sourceId, id = trackId, filepath = created.toString(),
            size = originalBytes.size.toLong(), sha256 = sha(originalBytes),
            verifiedAt = 1_700_000_000_000, algorithm = "sha256")
    }

    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            val imm = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
        }
        compose.waitForIdle()
    }

    private fun completeOnboardingIfPresent() {
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("first_run_wizard").fetchSemanticsNodes().isEmpty()) return
        repeat(4) {
            compose.onNodeWithTag("wizard_next").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("wizard_next").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText("Biblioteca").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun id3(title: String, artist: String, album: String, genre: String,
                    audio: ByteArray): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val text = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
            return id.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(
                ((text.size ushr 24) and 0xFF).toByte(),
                ((text.size ushr 16) and 0xFF).toByte(),
                ((text.size ushr 8) and 0xFF).toByte(),
                (text.size and 0xFF).toByte(), 0, 0) + text
        }
        val frames = frame("TIT2", title) + frame("TPE1", artist) +
            frame("TALB", album) + frame("TCON", genre)
        val header = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(StandardCharsets.US_ASCII))
            write(byteArrayOf(3, 0, 0))
            write(byteArrayOf(
                ((frames.size ushr 21) and 0x7f).toByte(),
                ((frames.size ushr 14) and 0x7f).toByte(),
                ((frames.size ushr 7) and 0x7f).toByte(),
                (frames.size and 0x7f).toByte()))
        }.toByteArray()
        return header + frames + audio
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
