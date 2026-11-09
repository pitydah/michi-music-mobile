package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun treeIdentityIsDeterministicAndDocumentScoped() {
        assertEquals(treeTrackId("primary:Music/a.flac"), treeTrackId("primary:Music/a.flac"))
        assertFalse(treeTrackId("primary:Music/a.flac") == treeTrackId("primary:Music/b.flac"))
        assertTrue(treeTrackId("primary:Music/a.flac").startsWith("safroot_"))
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
