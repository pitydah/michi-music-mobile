package org.michimusic.player

import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Contract test between AudioController's MediaItem construction and
// LibraryProvider.resolveForPlayback (invoked from
// MichiMediaLibrarySessionCallback.onSetMediaItems inside MichiPlaybackService). This is
// the exact point that would have caught the on-device bug: AudioController used to send
// mediaId = track.id ("local_...") while resolveForPlayback only recognizes ids built via
// LibraryProvider.buildSongId(), so every queue resolved to an empty list and ExoPlayer
// never received anything to play - confirmed on-device via MichiPlaybackDebug logs:
// received=2 ids=[local_1000258014, local_1000380277] -> resolved=0 ids=[].
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryProviderTest {

    private class FakeLocalMediaRepository(private val tracks: List<Track>) : LocalMediaRepository() {
        override suspend fun loadTracks(): List<Track> = tracks
    }

    private fun providerWithTracks(tracks: List<Track>): LibraryProvider {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return LibraryProvider(context, FakeLocalMediaRepository(tracks))
    }

    @Test
    fun `resolveForPlayback resolves an item built with the canonical song mediaId AudioController now sends`() = runTest {
        val track = Track(
            id = "local_1000258014",
            title = "Test Song",
            filepath = "content://media/external/audio/media/1000258014",
        )
        val provider = providerWithTracks(listOf(track))
        provider.refresh()

        val queuedItem = MediaItem.Builder()
            .setMediaId(LibraryProvider.buildSongId(track.id))
            .setUri(track.filepath)
            .build()

        val resolved = provider.resolveForPlayback(listOf(queuedItem))

        assertTrue("resolveForPlayback must not discard a canonically-formatted mediaId", resolved.isNotEmpty())
        assertEquals(1, resolved.size)
        assertEquals("song/local_1000258014", resolved.single().mediaId)
    }

    @Test
    fun `a full two-track queue built the way AudioController sends it resolves without dropping any item`() = runTest {
        val tracks = listOf(
            Track(id = "local_1000258014", title = "Song A", filepath = "content://media/external/audio/media/1000258014"),
            Track(id = "local_1000380277", title = "Song B", filepath = "content://media/external/audio/media/1000380277"),
        )
        val provider = providerWithTracks(tracks)
        provider.refresh()

        val queueMediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(LibraryProvider.buildSongId(track.id))
                .setUri(track.filepath)
                .build()
        }

        val resolved = provider.resolveForPlayback(queueMediaItems)

        assertEquals(2, resolved.size)
        assertEquals(
            listOf("local_1000258014", "local_1000380277"),
            resolved.map { LibraryProvider.extractSongId(it.mediaId) },
        )
    }

    @Test
    fun `the pre-fix raw track id mediaId is discarded, reproducing the on-device bug`() = runTest {
        val track = Track(id = "local_1000258014", title = "Test Song")
        val provider = providerWithTracks(listOf(track))
        provider.refresh()

        // Mirrors AudioController's mediaId construction *before* the fix:
        // .setMediaId(track.id) instead of .setMediaId(LibraryProvider.buildSongId(track.id)).
        val rawIdItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.filepath)
            .build()

        val resolved = provider.resolveForPlayback(listOf(rawIdItem))

        assertTrue(
            "A plain track.id must not resolve - this pins the exact contract the bug violated",
            resolved.isEmpty(),
        )
    }

    // --- lifecycle bug: cachedTracks stays emptyList() unless something loads it ---

    @Test
    fun `ensureLoaded populates the cache without a manual refresh call, mirroring the real onSetMediaItems flow`() = runTest {
        val track = Track(
            id = "local_1000258014",
            title = "Test Song",
            filepath = "content://media/external/audio/media/1000258014",
        )
        val provider = providerWithTracks(listOf(track))

        // Deliberately NOT calling provider.refresh() here - that would be the artificial
        // precondition that hid the real bug. ensureLoaded() is the exact mechanism the
        // fixed MichiMediaLibrarySessionCallback now calls before resolving.
        provider.ensureLoaded()

        val queuedItem = MediaItem.Builder()
            .setMediaId(LibraryProvider.buildSongId(track.id))
            .setUri(track.filepath)
            .build()

        val resolved = provider.resolveForPlayback(listOf(queuedItem))

        assertEquals(1, resolved.size)
        assertEquals("song/local_1000258014", resolved.single().mediaId)
    }

    @Test
    fun `resolveForPlayback still returns empty if nothing ever loaded the cache, pinning the original regression`() = runTest {
        val track = Track(id = "local_1000258014", title = "Test Song")
        val provider = providerWithTracks(listOf(track))
        // No refresh(), no ensureLoaded() - reproduces cachedTracks == emptyList() exactly
        // as it was on-device before this fix, so this test would fail if resolveForPlayback
        // were ever changed to silently self-load in a way that masks the lifecycle contract.

        val queuedItem = MediaItem.Builder()
            .setMediaId(LibraryProvider.buildSongId(track.id))
            .setUri(track.filepath)
            .build()

        val resolved = provider.resolveForPlayback(listOf(queuedItem))

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `ensureLoaded only triggers refresh once across multiple calls`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val countingRepo = CountingLocalMediaRepository(emptyList())
        val provider = LibraryProvider(context, countingRepo)

        provider.ensureLoaded()
        // refresh() itself fans out to loadTracks() more than once internally (directly,
        // plus via loadAlbums()/loadArtists()) - that's pre-existing and unrelated to this
        // fix, so we don't pin that internal count. What this test pins is that a SECOND and
        // THIRD ensureLoaded() call add zero additional repository hits.
        val countAfterFirstLoad = countingRepo.loadTracksCallCount
        assertTrue("the first ensureLoaded() must actually query the repository", countAfterFirstLoad > 0)

        provider.ensureLoaded()
        provider.ensureLoaded()

        assertEquals(
            "ensureLoaded must not re-query MediaStore on every subsequent playback command",
            countAfterFirstLoad,
            countingRepo.loadTracksCallCount,
        )
    }

    private class CountingLocalMediaRepository(private val tracks: List<Track>) : LocalMediaRepository() {
        var loadTracksCallCount = 0
            private set

        override suspend fun loadTracks(): List<Track> {
            loadTracksCallCount++
            return tracks
        }
    }
}
