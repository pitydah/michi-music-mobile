package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumDetailUiPolicyTest {
    @Test fun trackHeadingDoesNotRepeatTheTrackCount() {
        assertEquals("Pistas", albumDetailTrackHeading())
    }

    @Test fun albumMenuActionsSelectAtMostOneModal() {
        assertEquals(AlbumDetailModal.QUALITY, albumModalForMenuAction(AlbumMenuAction.QUALITY))
        assertEquals(AlbumDetailModal.HISTORY, albumModalForMenuAction(AlbumMenuAction.HISTORY))
        assertNull(albumModalForMenuAction(AlbumMenuAction.CLOSE))
    }
}
