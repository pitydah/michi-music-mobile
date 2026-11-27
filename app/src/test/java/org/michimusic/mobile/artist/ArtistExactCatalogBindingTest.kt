package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

class ArtistExactCatalogBindingTest {
    private fun song(id: String, credit: String, album: String="a") = Track(
        id=id, title=id, artist=credit, sourceId="device", filepath="content://music/$id", albumId=album,
    )
    private fun group(id: String, credit: String, vararg songs: Track) =
        LocalAlbum(Album(id=id, artist=credit, title=id, trackCount=songs.size),songs.toList())
    private fun ready(groups:List<LocalAlbum>, aliases:List<String>, mbid: String?=null):ArtistExactMembershipSnapshot {
        val rows = exactArtistCatalogOccurrences(groups,"lib",aliases.associateWith { mbid })
        val members = rows.filter { it.literalCredit in aliases }
        val entry = ArtistPresentationEntry(aliases.first(), aliases, members.map { it.albumId }.distinct(), members.size, "lib", mbid)
        val p=ArtistExactMembershipProjection.prepare(entry,rows,"lib")
        assertTrue(p is ArtistExactMembershipResult.Ready)
        return (p as ArtistExactMembershipResult.Ready).snapshot
    }
    @Test fun `guest credit selects just its one song`() {
        val g=group("a","Various Artists",song("1","Guest"), song("2","Other"))
        val snap=ready(listOf(g),listOf("Guest"))
        val out=exactArtistCatalogAlbums(listOf(g),snap)
        assertEquals(listOf("1"),out!!.single().tracks.map { it.id })
        assertEquals(1,out!!.single().album.trackCount)
    }
    @Test fun `verified aliases preserve exact music across albums`() {
        val mbid="2c91aa7e-c959-453e-8df2-c3d32c3e78aa"
        val groups=listOf(group("a","A-ha",song("1","A-ha")), group("b","a-ha",song("2","a-ha")))
        val snap=ready(groups,listOf("A-ha","a-ha"),mbid)
        assertEquals(setOf("a","b"),exactArtistCatalogAlbums(groups,snap)!!.map{it.album.id}.toSet())
    }
    @Test fun `album with foreign credit cannot be borrowed`() {
        val g=group("a","A",song("1","A"),song("2","B"))
        val snap=ready(listOf(g),listOf("A"))
        assertNull(exactArtistCatalogAlbums(listOf(group("a","A",song("1","B"),song("2","A"))),snap))
    }
    @Test fun `uri change invalidates snapshot`() {
        val g=group("a","A",song("1","A"))
        val snap=ready(listOf(g),listOf("A"))
        assertNull(exactArtistCatalogAlbums(listOf(group("a","A",song("1","A").copy(filepath="content://other"))),snap))
    }
    @Test fun `duplicate album id fails closed`() {
        val g=group("a","A",song("1","A"))
        val snap=ready(listOf(g),listOf("A"))
        assertNull(exactArtistCatalogAlbums(listOf(g,g),snap))
    }
    @Test fun `empty album sentinel is non playable`() {
        val g=group("a","A")
        val snap=ready(listOf(g),listOf("A"))
        assertTrue(exactArtistCatalogAlbums(listOf(g),snap)!!.single().tracks.isEmpty())
    }
    @Test fun `duplicate playback ids fail closed`() {
        val a=group("a","A",song("same","A","a"))
        val b=group("b","A",song("same","A","b").copy(sourceId="another"))
        assertNull(exactArtistCatalogAlbums(listOf(a,b),ready(listOf(a,b),listOf("A"))))
    }
    @Test fun `foreign library cannot be presented`() {
        val g=group("a","A",song("1","A"))
        val snap=ready(listOf(g),listOf("A"))
        assertFalse(snap.revalidate(ArtistPresentationEntry("A",listOf("A"),listOf("a"),1,"other",null), exactArtistCatalogOccurrences(listOf(g),"other"),"other"))
    }
}
