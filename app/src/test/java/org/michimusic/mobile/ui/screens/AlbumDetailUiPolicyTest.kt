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

    @Test fun albumPlaylistIsDisabledAndExplainedWhenRegisteredTracksAreUnavailable() {
        val state = albumPlaylistActionState(totalTrackCount = 3, eligibleTrackCount = 0)

        assertEquals(false, state.enabled)
        assertEquals(
            "No hay pistas disponibles para añadir a una lista en esta biblioteca.",
            state.unavailableMessage,
        )
    }

    @Test fun albumPlaylistUsesTheExactEligiblePayloadAsItsEnablementPolicy() {
        assertEquals(
            AlbumPlaylistActionState(enabled = true, unavailableMessage = null),
            albumPlaylistActionState(totalTrackCount = 3, eligibleTrackCount = 1),
        )
        assertEquals(
            AlbumPlaylistActionState(enabled = false, unavailableMessage = null),
            albumPlaylistActionState(totalTrackCount = 0, eligibleTrackCount = 0),
        )
    }
}
