package org.michimusic.mobile.artist

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

class ArtistExactNavigationPolicyTest {
    private fun song(id:String, credit:String) = Track(id=id, title=id, artist=credit,
        sourceId="s", albumId="a", filepath="content://$id")
    private fun album(artist:String, vararg songs:Track) =
        LocalAlbum(Album("a","Album",artist,trackCount=songs.size),songs.toList())
    private fun entry(aliases:List<String> =listOf("A"),count:Int=2) =
        ArtistPresentationEntry("A",aliases,listOf("a"),count,"lib",null)
    @Test fun simpleExactCreditRetainsExistingEnrichmentRoute() {
        assertTrue(ArtistExactNavigationPolicy.canUseStandardDetail(entry(),listOf(album("A",song("1","A"),song("2","A")))))
    }
    @Test fun albumArtistWithGuestMustUseExactRoute() {
        assertFalse(ArtistExactNavigationPolicy.canUseStandardDetail(entry(count=1),listOf(album("A",song("1","A"),song("2","Guest")))))
    }
    @Test fun guestCreditDoesNotOwnEntireAlbum() {
        assertFalse(ArtistExactNavigationPolicy.canUseStandardDetail(entry(count=1),listOf(album("Various",song("1","A"),song("2","Other")))))
    }
    @Test fun verifiedAliasesCannotGoViaLiteralDetail() {
        assertFalse(ArtistExactNavigationPolicy.canUseStandardDetail(entry(aliases=listOf("A","a")),listOf(album("A",song("1","A"),song("2","A")))))
    }
    @Test fun missingOrDuplicatedAlbumBlocksLegacyNavigation() {
        val group=album("A",song("1","A"),song("2","A"))
        assertFalse(ArtistExactNavigationPolicy.canUseStandardDetail(entry(),emptyList()))
        assertFalse(ArtistExactNavigationPolicy.canUseStandardDetail(entry(),listOf(group,group)))
    }
    @Test fun extraAlbumWithSameArtistCannotLeakViaLegacyDetail() {
        val owned=album("A",song("1","A"),song("2","A"))
        val foreign=LocalAlbum(Album("extra","Extra","A",trackCount=1),
            listOf(song("3","Guest").copy(albumId="extra")))
        assertFalse(ArtistExactNavigationPolicy.canUseStandardDetail(entry(),listOf(owned,foreign)))
    }
    @Test fun missingDeclaredTracksBlocksLegacyNavigation() {
        assertFalse(ArtistExactNavigationPolicy.canUseStandardDetail(entry(),listOf(album("A",song("1","A")))))
    }
}
