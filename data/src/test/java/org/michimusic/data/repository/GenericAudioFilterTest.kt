package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-function coverage for the H3 fix: isLikelyNonMusicGenericAudio() is the last-resort
// filter for rows that survive isRealTrack() and isNonMusicAudio() (see TrackCategoryFilterTest
// and NonMusicFolderFilterTest) but are still hash/UUID-named app cache blobs, e.g. the
// "8b652efb122f64..." case found on device. It requires several strong signals to agree at
// once - unknown artist AND unknown album AND a generated-looking title AND a suspicious
// folder - so a real song missing exactly one of these is never excluded.
//
// Download gets a narrower, additional exception on top of that: a generated-looking title
// with unknown artist, an unknown-or-"Download" album, AND a short duration
// (< 60s) is excluded even inside Download, since that is the exact signature the
// on-device "8b652efb..." track left. Any single one of those signals missing - a real
// artist, a real album, a normal title, or a duration of a real song - keeps Download safe.
class GenericAudioFilterTest {

    private val hexHash = "8b652efb122f64a1c9d3e7f0b2a4c6d8"
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"

    // Neutral duration for cases unrelated to the Download-duration exception - well above the
    // short-clip threshold so it never accidentally trips that branch.
    private val longDurationMs = 200_000L

    @Test
    fun longHexTitle_unknownMetadata_suspiciousFolder_isExcluded() {
        assertTrue(
            isLikelyNonMusicGenericAudio(
                title = hexHash,
                artist = null,
                album = null,
                relativePath = "Android/data/com.some.app/cache/",
                bucketDisplayName = null,
                durationMs = longDurationMs,
            ),
        )
    }

    @Test
    fun uuidTitle_unknownMetadata_suspiciousFolder_isExcluded() {
        assertTrue(
            isLikelyNonMusicGenericAudio(
                title = uuid,
                artist = "<unknown>",
                album = "<unknown>",
                relativePath = "Android/media/temp/",
                bucketDisplayName = null,
                durationMs = longDurationMs,
            ),
        )
    }

    @Test
    fun uuidTitle_unknownMetadata_musicFolder_isNotExcluded() {
        // Same hash-like title and missing tags as above, but sitting in a recognized music
        // folder - the folder protection must win.
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = uuid,
                artist = "<unknown>",
                album = "<unknown>",
                relativePath = "Music/",
                bucketDisplayName = "Music",
                durationMs = longDurationMs,
            ),
        )
    }

    @Test
    fun realSong_missingTagsInDownload_isNotExcluded() {
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = "My Favorite Song",
                artist = "",
                album = "",
                relativePath = "Download/",
                bucketDisplayName = "Download",
                durationMs = longDurationMs,
            ),
        )
    }

    @Test
    fun realSong_missingTagsInMusic_isNotExcluded() {
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = "Sunset Groove",
                artist = null,
                album = null,
                relativePath = "Music/",
                bucketDisplayName = "Music",
                durationMs = longDurationMs,
            ),
        )
    }

    @Test
    fun oddTitle_withKnownArtist_isNotExcluded() {
        // Title alone looks generated and the folder alone looks suspicious, but a known
        // artist is present - the combination requirement blocks exclusion.
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = "xk29fj203mfaslkdjf982314",
                artist = "Some Artist",
                album = "",
                relativePath = "Android/data/com.some.app/cache/",
                bucketDisplayName = null,
                durationMs = longDurationMs,
            ),
        )
    }

    @Test
    fun normalTitle_withUnknownMetadata_isNotExcluded() {
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = "Track 05",
                artist = "<unknown>",
                album = "<unknown>",
                relativePath = "Android/data/com.some.app/cache/",
                bucketDisplayName = null,
                durationMs = longDurationMs,
            ),
        )
    }

    @Test
    fun legitimateTitleWithNumbers_isNotExcluded() {
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = "24K Magic",
                artist = "<unknown>",
                album = "<unknown>",
                relativePath = "Android/data/com.some.app/cache/",
                bucketDisplayName = null,
                durationMs = longDurationMs,
            ),
        )
    }

    @Test
    fun unrecognizedCustomFolder_isNotTreatedAsSuspicious() {
        // Hash-like title, unknown tags, but the folder is a normal custom folder the app
        // has never seen before - not Music/Download, but not cache/temp either. Per the
        // "normal custom folder stays conservative" rule this must not be excluded.
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = hexHash,
                artist = null,
                album = null,
                relativePath = "MyStuff/Road Trip Mix/",
                bucketDisplayName = "Road Trip Mix",
                durationMs = longDurationMs,
            ),
        )
    }

    // --- Download-specific short generic-audio exception (on-device "8b652efb..." case) ---

    @Test
    fun hashTitle_unknownArtist_downloadAlbum_downloadFolder_shortDuration_isExcluded() {
        // The exact real-world signature: MediaStore fills ALBUM with the literal folder name
        // "Download" for this untagged clip.
        assertTrue(
            isLikelyNonMusicGenericAudio(
                title = hexHash,
                artist = "<unknown>",
                album = "Download",
                relativePath = "Download/",
                bucketDisplayName = "Download",
                durationMs = 33_280L,
            ),
        )
    }

    @Test
    fun hashTitle_unknownArtist_downloadAlbum_downloadFolder_longDuration_isNotExcluded() {
        // Same signals, but a two-minute duration is a real song's length, not a clip's -
        // duration alone must never be the deciding signal, but it does gate this exception.
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = hexHash,
                artist = "<unknown>",
                album = "Download",
                relativePath = "Download/",
                bucketDisplayName = "Download",
                durationMs = 120_000L,
            ),
        )
    }

    @Test
    fun normalTitle_shortDuration_unknownArtist_inDownload_isNotExcluded() {
        // A real 30s song with a normal title and missing artist tag must stay - the
        // generated-title signal is required and is absent here.
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = "Sunset Groove",
                artist = "<unknown>",
                album = "Download",
                relativePath = "Download/",
                bucketDisplayName = "Download",
                durationMs = 30_000L,
            ),
        )
    }

    @Test
    fun hashTitle_shortDuration_knownArtist_inDownload_isNotExcluded() {
        // A known artist tag is present - the combination requirement blocks exclusion even
        // with a hash title and a short duration.
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = hexHash,
                artist = "Some Artist",
                album = "Download",
                relativePath = "Download/",
                bucketDisplayName = "Download",
                durationMs = 30_000L,
            ),
        )
    }

    @Test
    fun normalTitle_noTags_inDownload_isNotExcluded() {
        // Untagged real song with a normal title in Download - must remain permitted
        // regardless of duration.
        assertFalse(
            isLikelyNonMusicGenericAudio(
                title = "My Favorite Song",
                artist = null,
                album = null,
                relativePath = "Download/",
                bucketDisplayName = "Download",
                durationMs = 25_000L,
            ),
        )
    }

    // --- looksLikeGeneratedIdentifier() pure-function coverage ---

    @Test
    fun looksLikeGeneratedIdentifier_longHex_isTrue() {
        assertTrue(looksLikeGeneratedIdentifier(hexHash))
    }

    @Test
    fun looksLikeGeneratedIdentifier_uuid_isTrue() {
        assertTrue(looksLikeGeneratedIdentifier(uuid))
    }

    @Test
    fun looksLikeGeneratedIdentifier_shortWord_isFalse() {
        assertFalse(looksLikeGeneratedIdentifier("Hi"))
    }

    @Test
    fun looksLikeGeneratedIdentifier_normalTitleWithSpaces_isFalse() {
        assertFalse(looksLikeGeneratedIdentifier("24K Magic"))
    }

    @Test
    fun looksLikeGeneratedIdentifier_oddButShortTitle_isFalse() {
        assertFalse(looksLikeGeneratedIdentifier("xyzzy99"))
    }
}
