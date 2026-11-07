package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

class AlbumIdentityPolicyTest {
    private fun group(albumId: String, ids: List<String>, title: String = "Same title") =
        LocalAlbum(Album(id = albumId, title = title, artist = "Same artist", year = 1987),
            ids.map { Track(id = it, title = "Song $it", album = title, artist = "Same artist") })

    @Test fun sameRawIdDifferentMembershipNeverOpensAnotherAlbum() {
        val a = group("raw", listOf("a-1", "a-2"))
        val b = group("raw", listOf("b-1"))
        val list = listOf(a,b)
        assertNotEquals(albumNavigationIdentity(a, LibraryScope.All), albumNavigationIdentity(b, LibraryScope.All))
        assertSame(a, resolveNavigationAlbum(list, albumNavigationIdentity(a, LibraryScope.All), LibraryScope.All))
        assertSame(b, resolveNavigationAlbum(list, albumNavigationIdentity(b, LibraryScope.All), LibraryScope.All))
        assertNull(resolveNavigationAlbum(list, "raw", LibraryScope.All))
    }

    @Test fun identityStableOnReorderingButTracksAndCreditChangesInvalidateIt() {
        val a = group("raw", listOf("a-2", "a-1"))
        val b = group("raw", listOf("a-1", "a-2"))
        assertEquals(albumNavigationIdentity(a, LibraryScope.All), albumNavigationIdentity(b, LibraryScope.All))
        assertNotEquals(albumNavigationIdentity(a, LibraryScope.All), albumNavigationIdentity(group("raw", listOf("a-1")), LibraryScope.All))
        assertNotEquals(albumNavigationIdentity(a, LibraryScope.All), albumNavigationIdentity(group("raw", listOf("a-2", "a-1"), "new title"), LibraryScope.All))
    }

    @Test fun indistinguishableDuplicatesFailClosedAndMissingIdNeverFallsBack() {
        val a = group("same", listOf("one")); val copy = group("same", listOf("one"))
        val albums = listOf(a, copy, group("different", listOf("two")))
        assertEquals(2, albumNavigationIdentityCounts(albums, LibraryScope.All)[albumNavigationIdentity(a, LibraryScope.All)])
        assertNull(resolveNavigationAlbum(albums, albumNavigationIdentity(a, LibraryScope.All), LibraryScope.All))
        assertNull(resolveNavigationAlbum(albums, "alb2_" + "0".repeat(64), LibraryScope.All))
        assertFalse(albumNavigationIdentity(a, LibraryScope.All).contains("Same title"))
    }
    @Test fun changingLibraryScopeInvalidatesAnOtherwiseIdenticalAlbumToken() {
        val disc = group("raw", listOf("same-track"))
        val first = albumNavigationIdentity(disc, LibraryScope.One("phone"))
        val second = albumNavigationIdentity(disc, LibraryScope.One("nas"))
        assertNotEquals(first, second)
        assertNull(resolveNavigationAlbum(listOf(disc), first, LibraryScope.One("nas")))
        assertSame(disc, resolveNavigationAlbum(listOf(disc), first, LibraryScope.One("phone")))
    }

}
