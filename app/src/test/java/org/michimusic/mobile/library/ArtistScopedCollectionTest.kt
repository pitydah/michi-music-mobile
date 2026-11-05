package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.LibraryType
import org.michimusic.core.models.MusicLibrary
import org.michimusic.core.models.ResolvedTrack
import org.michimusic.core.models.PlayableLocation
import org.michimusic.core.models.TrackLocationType
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackIdentity
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

class ArtistScopedCollectionTest {
    private val one = MusicLibrary("one", "Phone", LibraryType.DEVICE_LOCAL)
    private val two = MusicLibrary("two", "NAS", LibraryType.SYNCED)
    private val a = Track("id-1", "The Dead Heart", artist = "Midnight Oil", album = "Diesel and Dust", year = 1987)
    private val b = Track("id-2", "Beds Are Burning", artist = "Midnight Oil", album = "Diesel and Dust", year = 1987)
    private val c = Track("id-3", "Blue", artist = "Other", album = "Blue Album")
    private val originals = listOf(
        LocalAlbum(Album("album-diesel", "Diesel and Dust", "Midnight Oil", year = 1987), listOf(a, b)),
        LocalAlbum(Album("album-blue", "Blue Album", "Other"), listOf(c)),
    )
    private fun resolved(track: Track): ResolvedTrack = ResolvedTrack(
        identity = TrackIdentity(track.id, track.title, track.artist, track.album, 10000L),
        locations = emptyList(), preferredLocation = null,
    )

    @Test fun oneLibraryShowsOnlyMembershipTracksAndRecountsAlbum() {
        val state = resolveArtistScopedCollection(originals, LibraryScope.One("one"), listOf(one,two), listOf(resolved(a)))
        assertTrue(state.ready)
        assertEquals("one", state.identityLibraryId)
        assertEquals(listOf("id-1"), state.albums.flatMap { it.tracks }.map { it.id })
        assertEquals(1, state.albums.single().album.trackCount)
        assertEquals(1987, state.albums.single().album.year)
    }

    @Test fun scopeChangeDoesNotExposeOldArtistEvenWhenNameMatches() {
        val scopeA = resolveArtistScopedCollection(originals, LibraryScope.One("one"), listOf(one,two), listOf(resolved(a)))
        val scopeB = resolveArtistScopedCollection(originals, LibraryScope.One("two"), listOf(one,two), listOf(resolved(b)))
        assertEquals(listOf("id-1"), scopeA.albums.flatMap { it.tracks }.map { it.id })
        assertEquals(listOf("id-2"), scopeB.albums.flatMap { it.tracks }.map { it.id })
        assertEquals("two", scopeB.identityLibraryId)
    }

    @Test fun allLocalLibraryResolvesTheCanonicalDeviceIdentity() {
        val state = resolveArtistScopedCollection(originals, LibraryScope.All, listOf(one,two), listOf(resolved(a)))
        // Artist Enrichment is local-only: MediaStore tracks belong to the device library,
        // so "All" with no remote content is not an ambiguous identity.
        assertEquals(org.michimusic.data.cache.MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, state.identityLibraryId)
        assertEquals(3, state.albums.flatMap { it.tracks }.size)
    }

    @Test fun allWithSyncedTracksKeepsTheIdentityUnresolved() {
        val synced = c.copy(id = "id-4", source = org.michimusic.core.models.TrackSource.SYNCED)
        val withRemote = originals + LocalAlbum(Album("album-nas", "NAS", "Other"), listOf(synced))
        val state = resolveArtistScopedCollection(withRemote, LibraryScope.All, listOf(one,two), listOf(resolved(a)))
        assertNull(state.identityLibraryId)
    }

    @Test fun oneLibraryEmptyMembershipNeverFallsBackToUnscopedMedia() {
        val state = resolveArtistScopedCollection(originals, LibraryScope.One("one"), listOf(one,two), emptyList())
        assertTrue(state.albums.isEmpty())
    }

    @Test fun registeredNonLocalTrackRemainsVisibleWithoutFabricatingCoverOrYear() {
        val remote = ResolvedTrack(TrackIdentity("remote", "Unknown Song", "Midnight Oil", "Head Injuries", 1000),emptyList(),null)
        val state = resolveArtistScopedCollection(originals, LibraryScope.One("two"), listOf(one,two), listOf(remote))
        assertEquals("Head Injuries", state.albums.single().album.title)
        assertEquals(0, state.albums.single().album.year)
        assertEquals("", state.albums.single().album.coverId)
    }
    @Test fun registeredOfflinePathIsBlankedAndIsNotPlayable() {
        val stale = a.copy(filepath = "content://media/stale")
        val local = listOf(LocalAlbum(originals.first().album, listOf(stale)))
        val state = resolveArtistScopedCollection(local, LibraryScope.One("one"), listOf(one), listOf(resolved(stale)))
        assertEquals("", state.albums.single().tracks.single().filepath)
        assertTrue(state.playableTrackIds.isEmpty())
    }
    @Test fun preferredLocationOverridesStaleLocalPathAndMakesTrackEligible() {
        val stale = a.copy(filepath = "content://media/stale")
        val playable = ResolvedTrack(
            TrackIdentity(a.id, a.title, a.artist, a.album, 12000L), emptyList(),
            PlayableLocation("loc-1", "content://media/current", TrackLocationType.MEDIASTORE),
        )
        val state = resolveArtistScopedCollection(
            listOf(LocalAlbum(originals.first().album, listOf(stale))), LibraryScope.One("one"),
            listOf(one), listOf(playable),
        )
        assertEquals("content://media/current", state.albums.single().tracks.single().filepath)
        assertEquals(setOf("id-1"), state.playableTrackIds)
    }
    @Test fun unregisteredLegacyMediaIsPlayableOnlyInAllScope() {
        val legacy = a.copy(filepath = "content://media/available")
        val local = listOf(LocalAlbum(originals.first().album, listOf(legacy)))
        val all = resolveArtistScopedCollection(local, LibraryScope.All, listOf(one, two), emptyList())
        val oneScope = resolveArtistScopedCollection(local, LibraryScope.One("one"), listOf(one, two), emptyList())
        assertEquals(setOf("id-1"), all.playableTrackIds)
        assertTrue(oneScope.playableTrackIds.isEmpty())
    }
    @Test fun unregisteredGarbageUrlIsNotEligible() {
        assertFalse(artistLegacyUriIsPlayableCandidate("mock://uri"))
        assertFalse(artistLegacyUriIsPlayableCandidate(""))
        assertFalse(artistLegacyUriIsPlayableCandidate("../relative.mp3"))
        assertTrue(artistLegacyUriIsPlayableCandidate("content://media/file/1"))
    }
    @Test fun allScopeNeverOverridesRegisteredOfflineStateWithLegacyPath() {
        val stale = a.copy(filepath = "content://media/stale")
        val local = listOf(LocalAlbum(originals.first().album, listOf(stale)))
        val state = resolveArtistScopedCollection(local, LibraryScope.All, listOf(one,two), listOf(resolved(stale)))
        assertTrue(state.playableTrackIds.isEmpty())
        assertEquals("", state.albums.single().tracks.single().filepath)
    }
}
