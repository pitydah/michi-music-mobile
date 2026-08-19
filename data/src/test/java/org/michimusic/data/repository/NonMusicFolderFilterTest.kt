package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-function coverage for the Media-Fix: isNonMusicAudio() decides which rows that pass
// isRealTrack() (see TrackCategoryFilterTest) get excluded for being voice/call recordings
// or chat-app/video-editor exports, without relying on IS_MUSIC or on tag quality.
class NonMusicFolderFilterTest {

    @Test
    fun recordingFlagged_isExcluded() {
        assertTrue(isNonMusicAudio(isRecording = true, relativePath = null, bucketDisplayName = null))
    }

    @Test
    fun whatsAppFolder_relativePath_isExcluded() {
        assertTrue(
            isNonMusicAudio(
                isRecording = false,
                relativePath = "Music/WhatsApp Audio/",
                bucketDisplayName = null,
            ),
        )
    }

    @Test
    fun whatsAppFolder_bucketDisplayName_isExcluded() {
        assertTrue(
            isNonMusicAudio(isRecording = false, relativePath = null, bucketDisplayName = "WhatsApp Audio"),
        )
    }

    @Test
    fun inShotFolder_isExcluded() {
        assertTrue(
            isNonMusicAudio(isRecording = false, relativePath = "Movies/InShot/", bucketDisplayName = null),
        )
    }

    @Test
    fun voiceRecorderFolder_isExcluded() {
        assertTrue(
            isNonMusicAudio(isRecording = false, relativePath = null, bucketDisplayName = "Voice Recorder"),
        )
    }

    @Test
    fun callRecordingsFolder_isExcluded() {
        assertTrue(
            isNonMusicAudio(isRecording = false, relativePath = "Recordings/Call Recordings/", bucketDisplayName = null),
        )
    }

    @Test
    fun folderMatch_isCaseInsensitive() {
        assertTrue(
            isNonMusicAudio(isRecording = false, relativePath = "MUSIC/WHATSAPP AUDIO/", bucketDisplayName = null),
        )
    }

    @Test
    fun musicFolder_isNotExcluded() {
        assertFalse(
            isNonMusicAudio(isRecording = false, relativePath = "Music/", bucketDisplayName = "Music"),
        )
    }

    @Test
    fun downloadFolder_isNotExcluded() {
        assertFalse(
            isNonMusicAudio(isRecording = false, relativePath = "Download/", bucketDisplayName = "Download"),
        )
    }

    @Test
    fun customUserFolder_isNotExcluded() {
        assertFalse(
            isNonMusicAudio(isRecording = false, relativePath = "Music/Road Trip Mix/", bucketDisplayName = "Road Trip Mix"),
        )
    }

    @Test
    fun missingFolderInfo_isNotExcluded() {
        assertFalse(isNonMusicAudio(isRecording = false, relativePath = null, bucketDisplayName = null))
    }

    // isNonMusicAudio() takes no artist/album/title parameters at all, so a real song with
    // missing or poor tags sitting in Music/Download can never be excluded by this function -
    // only its folder location or the IS_RECORDING flag can.
    @Test
    fun untaggedTrackInMusicOrDownloadFolder_isNotExcludedByMissingMetadata() {
        assertFalse(
            isNonMusicAudio(isRecording = false, relativePath = "Music/", bucketDisplayName = "Music"),
        )
        assertFalse(
            isNonMusicAudio(isRecording = false, relativePath = "Download/", bucketDisplayName = "Download"),
        )
    }
}
