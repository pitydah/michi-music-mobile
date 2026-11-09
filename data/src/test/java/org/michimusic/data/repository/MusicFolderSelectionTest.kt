package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFolderSelectionTest {
    @Test
    fun topLevelFolderUsesFirstRelativeSegment() {
        assertEquals("music", topLevelFolder("Music/Album/song.mp3", "Music"))
        assertEquals("download", topLevelFolder("Download/song.mp3", "Download"))
        assertEquals("music", topLevelFolder("\\Music\\Album\\song.mp3", null))
    }

    @Test
    fun topLevelFolderFallsBackToBucketAndNormalizesCase() {
        assertEquals("ringtones", topLevelFolder("", "Ringtones"))
        assertEquals("custom", topLevelFolder("  Custom/  ", null))
        assertNull(topLevelFolder(null, null))
        assertNull(topLevelFolder("   ", "  "))
    }

    @Test
    fun explicitSelectionMatchesFolderCaseInsensitively() {
        assertTrue(isFolderIncluded("Music/Album/song.mp3", null, setOf("music")))
        assertTrue(isFolderIncluded("Download/song.mp3", null, setOf("DOWNLOAD")))
        assertFalse(isFolderIncluded("Podcasts/show.mp3", null, setOf("music")))
    }

    @Test
    fun wildcardSelectsEveryFolder() {
        assertTrue(isFolderIncluded("WhatsApp/voice.mp3", null, setOf(ALL_FOLDERS)))
        assertTrue(isFolderIncluded(null, null, setOf(ALL_FOLDERS)))
    }

    @Test
    fun smartDefaultKeepsMusicTaggedAndConventionalFolders() {
        val selection = smartIncludedFolders(
            foldersWithMusic = setOf("music", "mixtapes"),
            knownFolders = setOf("music", "download", "mixtapes", "whatsapp", "ringtones"),
        )

        assertEquals(setOf("music", "mixtapes", "download"), selection)
        assertFalse(isFolderIncluded("WhatsApp/voice.opus", null, selection))
        assertFalse(isFolderIncluded("Ringtones/theme.mp3", null, selection))
    }

    @Test
    fun smartDefaultDoesNotInventUnknownFolders() {
        val selection = smartIncludedFolders(
            foldersWithMusic = emptySet(),
            knownFolders = setOf("whatsapp", "ringtones"),
        )

        assertTrue(selection.isEmpty())
    }

    @Test
    fun defaultSelectionFallsBackToAllKnownFoldersWithoutMusicEvidence() {
        val folders = listOf(
            MediaFolder(name = "whatsapp", trackCount = 2, musicTrackCount = 0),
            MediaFolder(name = "ringtones", trackCount = 1, musicTrackCount = 0),
        )

        assertEquals(setOf("whatsapp", "ringtones"), defaultFolderSelection(folders))
    }

    @Test
    fun untaggedSongsInMusicAndDownloadRemainAvailableInSmartDefault() {
        val selection = smartIncludedFolders(
            foldersWithMusic = emptySet(),
            knownFolders = setOf("music", "download", "recordings"),
        )

        assertTrue(isFolderIncluded("Music/untagged.mp3", null, selection))
        assertTrue(isFolderIncluded("Download/untagged.mp3", null, selection))
        assertFalse(isFolderIncluded("Recordings/meeting.m4a", null, selection))
    }
}
