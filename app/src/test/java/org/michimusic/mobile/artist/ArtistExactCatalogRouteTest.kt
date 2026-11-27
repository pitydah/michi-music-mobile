package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.data.repository.MusicRootStamp
import org.michimusic.mobile.navigation.ArtistExactMembershipTicketStore
import org.michimusic.mobile.navigation.ArtistReviewRootGuard

class ArtistExactCatalogRouteTest {
    private val root=ArtistReviewRootGuard(MusicRootStamp("root",3L),3L)
    private val mbid="a374f448-f70b-49a6-a3a7-889e625ac9a0"
    private fun rows()=listOf(
        ArtistMembershipOccurrence("lib","album-1","A-ha","1",mbid),
        ArtistMembershipOccurrence("lib","album-2","a-ha","2",mbid),
    )
    private fun entry()=ArtistPresentationEntry("A-ha",listOf("A-ha","a-ha"),listOf("album-1","album-2"),2,"lib",mbid)
    @Test fun `success with fresh full catalog`() {
        val store=ArtistExactMembershipTicketStore { 100 }
        val token=store.issue(root,"lib",entry(),rows())!!
        assertEquals(mbid,store.resolveAgainstCatalog(token,root,"lib",listOf(entry()),rows())!!.verifiedMbid)
    }
    @Test fun `different album invalidates and revokes`() {
        val store=ArtistExactMembershipTicketStore { 100 }
        val token=store.issue(root,"lib",entry(),rows())!!
        assertNull(store.resolveAgainstCatalog(token,root,"lib",listOf(entry()),rows().dropLast(1)))
        assertNull(store.preview(token,root))
    }
    @Test fun `alias mismatch is rejected`() {
        val store=ArtistExactMembershipTicketStore { 100 }
        val token=store.issue(root,"lib",entry(),rows())!!
        assertNull(store.resolveAgainstCatalog(token,root,"lib",listOf(entry().copy(mergedCredits=listOf("A-ha"))),rows()))
    }
    @Test fun `duplicate entries are not selected by first-match`() {
        val store=ArtistExactMembershipTicketStore { 100 }
        val token=store.issue(root,"lib",entry(),rows())!!
        assertNull(store.resolveAgainstCatalog(token,root,"lib",listOf(entry(),entry()),rows()))
    }
    @Test fun `preview is not valid after root change`() {
        val store=ArtistExactMembershipTicketStore { 100 }
        val token=store.issue(root,"lib",entry(),rows())!!
        assertNull(store.preview(token,ArtistReviewRootGuard(MusicRootStamp("root",3L),4L)))
    }
    @Test fun `cannot resolve from ALL or a foreign library`() {
        val store=ArtistExactMembershipTicketStore { 100 }
        val token=store.issue(root,"lib",entry(),rows())!!
        assertNull(store.resolveAgainstCatalog(token,root,"other",listOf(entry()),rows()))
    }
}
