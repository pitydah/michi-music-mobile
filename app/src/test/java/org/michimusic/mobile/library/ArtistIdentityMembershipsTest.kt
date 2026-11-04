package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.LibraryType
import org.michimusic.core.models.MusicLibrary
import org.michimusic.core.models.ResolvedTrack
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackIdentity
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

class ArtistIdentityMembershipsTest {
    private val phone = MusicLibrary("phone", "Phone", LibraryType.DEVICE_LOCAL)
    private val nas = MusicLibrary("nas", "NAS", LibraryType.SYNCED)
    private val a = Track("track-a", "A", artist = "Artist A", album = "Album A")
    private val b = Track("track-b", "B", artist = "Artist A", album = "Album B")
    private val orphan = Track("orphan", "Z", artist = "Artist Z", album = "Album Z")
    private fun local(t: Track) = LocalAlbum(Album(t.album, t.album, t.artist), listOf(t))
    private fun res(t: Track) = ResolvedTrack(TrackIdentity(t.id, t.title, t.artist, t.album, 5000L), emptyList(), null)

    @Test fun allMembersBuildScopeFingerprintForEachLibrary() {
        val originals = listOf(local(a), local(b))
        val aggregate = resolveArtistScopedCollection(originals, LibraryScope.All,
            listOf(phone, nas), listOf(res(a), res(b)))
        val result = resolveArtistIdentityMemberships(aggregate, originals, listOf(phone, nas),
            mapOf("phone" to listOf(res(a)), "nas" to listOf(res(b))))
        assertTrue(result.ready)
        assertEquals(setOf("phone", "nas"), result.byCredit["Artist A"]?.map { it.libraryId }?.toSet())
        assertEquals(2, result.byCredit["Artist A"]?.size)
    }

    @Test fun unregisteredTracksBlockAClaimOfFullCombinedIdentity() {
        val originals = listOf(local(a), local(orphan))
        val aggregate = resolveArtistScopedCollection(originals, LibraryScope.All,
            listOf(phone, nas), listOf(res(a)))
        val result = resolveArtistIdentityMemberships(aggregate, originals, listOf(phone, nas),
            mapOf("phone" to listOf(res(a)), "nas" to emptyList()))
        assertTrue("Artist Z" in result.unscopedCredits)
        assertFalse("Artist Z" in result.byCredit)
        assertTrue("Artist A" in result.byCredit)
    }

    @Test fun partialMembershipSnapshotNeverClaimsCompleteIdentity() {
        val originals = listOf(local(a))
        val aggregate = resolveArtistScopedCollection(originals, LibraryScope.All,
            listOf(phone, nas), listOf(res(a)))
        val result = resolveArtistIdentityMemberships(aggregate, originals, listOf(phone, nas),
            mapOf("phone" to listOf(res(a))))
        assertFalse(result.ready)
        assertTrue(result.byCredit.isEmpty())
    }
}
