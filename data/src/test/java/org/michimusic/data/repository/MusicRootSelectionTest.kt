package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.net.Uri

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MusicRootSelectionTest {
    @Test
    fun onlyMusicAndItsSubfoldersBelongToTheLocalRoot() {
        assertTrue(isInsideMusicRoot("Music/song.flac"))
        assertTrue(isInsideMusicRoot("Music/Rock/Pink Floyd/The Wall/track.mp3"))
        assertTrue(isInsideMusicRoot("\\Music\\Jazz\\track.mp3"))
        assertTrue(isInsideMusicRoot("music"))
        assertFalse(isInsideMusicRoot("Download/song.mp3"))
        assertFalse(isInsideMusicRoot("Ringtones/theme.mp3"))
        assertFalse(isInsideMusicRoot(null))
        assertFalse(isInsideMusicRoot(""))
    }

    @Test
    fun mediaStoreSelectionFiltersAtTheProviderBoundary() {
        val selection = mediaStoreMusicRootSelection("relative_path")

        assertEquals("relative_path LIKE ? OR relative_path = ?", selection)
        assertEquals(listOf("Music/%", "Music"), MUSIC_ROOT_QUERY_ARGS.toList())
    }

    @Test
    fun audioDocumentsAcceptMimeAndKnownExtensions() {
        assertTrue(isSupportedAudioDocument("audio/flac", "track.bin"))
        assertTrue(isSupportedAudioDocument("application/ogg", "track"))
        assertTrue(isSupportedAudioDocument(null, "track.FLAC"))
        assertFalse(isSupportedAudioDocument("image/jpeg", "cover.jpg"))
        assertFalse(isSupportedAudioDocument("video/mp4", "clip.bin"))
    }

    @Test
    fun treeIdentityIsDeterministicAndProviderScoped() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val otherProvider = Uri.parse("content://com.example.other.documents/tree/primary%3AMusic")

        assertEquals(treeTrackId(tree, "primary:Music/a.flac"), treeTrackId(tree, "primary:Music/a.flac"))
        assertFalse(treeTrackId(tree, "primary:Music/a.flac") == treeTrackId(tree, "primary:Music/b.flac"))
        assertNotEquals(
            treeTrackId(tree, "primary:Music/a.flac"),
            treeTrackId(otherProvider, "primary:Music/a.flac"),
        )
        assertTrue(treeTrackId(tree, "primary:Music/a.flac").startsWith("safroot_"))
    }

    @Test
    fun javaHashCollisionsCannotCollapseTwoDocuments() {
        // "Aa" and "BB" are the canonical equal Java hashCode pair.
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(treeTrackId(tree, "Aa"), treeTrackId(tree, "BB"))
    }

    @Test
    fun customRootSourceIsStableAndScopedByProviderAndTree() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val renamedTree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusicRenamed")
        val otherProvider = Uri.parse("content://com.example.other.documents/tree/primary%3AMusic")

        assertEquals(customRootSourceId(tree), customRootSourceId(tree))
        assertNotEquals(customRootSourceId(tree), customRootSourceId(renamedTree))
        assertNotEquals(customRootSourceId(tree), customRootSourceId(otherProvider))
    }

    @Test
    fun defaultRootIsMusicAndCustomRootKeepsItsLabel() {
        val default = MusicRoot.Default
        val custom = MusicRoot(
            type = MusicRootType.CUSTOM_TREE,
            treeUri = "content://tree/music",
            displayName = "Mi biblioteca",
        )

        assertTrue(default.isDefault)
        assertEquals("Music/", default.label)
        assertFalse(custom.isDefault)
        assertEquals("Mi biblioteca", custom.label)
    }
}
