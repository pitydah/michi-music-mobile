package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-function coverage: isRealTrack() is what decides which MediaStore audio rows survive
// into the library now that the query no longer filters by IS_MUSIC. No Robolectric/
// MediaStore/cursor needed - the flags below are exactly what a cursor row would report for
// MediaStore.Audio.Media.IS_RINGTONE/IS_ALARM/IS_NOTIFICATION/IS_PODCAST/IS_AUDIOBOOK.
class TrackCategoryFilterTest {

    @Test
    fun trackNotTaggedAsMusic_isStillIncluded() {
        // The real-world case this fix targets: a downloaded song MediaStore never marked
        // is_music=1. isRealTrack() doesn't look at IS_MUSIC at all, so it's still accepted.
        assertTrue(
            isRealTrack(
                isRingtone = false,
                isAlarm = false,
                isNotification = false,
                isPodcast = false,
                isAudiobook = false,
            ),
        )
    }

    @Test
    fun trackTaggedAsMusic_isIncluded() {
        // is_music=1 is irrelevant to the new filter, but a properly tagged song must still
        // pass through it (it isn't any of the excluded categories either).
        assertTrue(
            isRealTrack(
                isRingtone = false,
                isAlarm = false,
                isNotification = false,
                isPodcast = false,
                isAudiobook = false,
            ),
        )
    }

    @Test
    fun ringtone_isExcluded() {
        assertFalse(
            isRealTrack(
                isRingtone = true,
                isAlarm = false,
                isNotification = false,
                isPodcast = false,
                isAudiobook = false,
            ),
        )
    }

    @Test
    fun alarm_isExcluded() {
        assertFalse(
            isRealTrack(
                isRingtone = false,
                isAlarm = true,
                isNotification = false,
                isPodcast = false,
                isAudiobook = false,
            ),
        )
    }

    @Test
    fun notification_isExcluded() {
        assertFalse(
            isRealTrack(
                isRingtone = false,
                isAlarm = false,
                isNotification = true,
                isPodcast = false,
                isAudiobook = false,
            ),
        )
    }

    @Test
    fun podcast_isExcluded() {
        assertFalse(
            isRealTrack(
                isRingtone = false,
                isAlarm = false,
                isNotification = false,
                isPodcast = true,
                isAudiobook = false,
            ),
        )
    }

    @Test
    fun audiobook_isExcluded() {
        assertFalse(
            isRealTrack(
                isRingtone = false,
                isAlarm = false,
                isNotification = false,
                isPodcast = false,
                isAudiobook = true,
            ),
        )
    }

    @Test
    fun multipleExcludedCategoriesAtOnce_isStillExcluded() {
        assertFalse(
            isRealTrack(
                isRingtone = true,
                isAlarm = false,
                isNotification = false,
                isPodcast = true,
                isAudiobook = false,
            ),
        )
    }
}
