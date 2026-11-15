package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MI-26 P0 3: read-only lookup is a different contract from a physical write.
 * Looking up the metadata of the track that is currently loaded must work; the
 * mutation contract keeps every strict guard.
 */
class MichiGenreAccessPolicyTest {
    private val policy = MichiGenreAccessPolicy
    private fun context(
        trackLocal: Boolean = true,
        receiptMatches: Boolean = true,
        uriMatches: Boolean = true,
        scopeUnchanged: Boolean = true,
        revisionUnchanged: Boolean = true,
        dialogOpen: Boolean = true,
        trackSelectedInPlayer: Boolean = false,
        remoteSessionActive: Boolean = false,
        handoffActive: Boolean = false,
        localEndpoint: Boolean = true,
    ) = MichiGenreAccessPolicy.Context(
        trackLocal, receiptMatches, uriMatches, scopeUnchanged, revisionUnchanged,
        dialogOpen, trackSelectedInPlayer, remoteSessionActive, handoffActive, localEndpoint,
    )

    @Test
    fun lookupIsAllowedWhileTrackPlaysOrIsPaused() {
        assertTrue(policy.canLookup(context(trackSelectedInPlayer = true)))
        // Pause does not change the receipt; the player still owns the descriptor.
        assertTrue(policy.canLookup(context(trackSelectedInPlayer = true, remoteSessionActive = false)))
        assertTrue(policy.canLookup(context(
            trackSelectedInPlayer = true, remoteSessionActive = true, handoffActive = true,
            localEndpoint = false,
        )))
        assertNull(policy.denialReasonForLookup(context(trackSelectedInPlayer = true)))
    }

    @Test
    fun lookupStillRequiresStableIdentityAndOpenDialog() {
        val cases = mapOf(
            context(trackLocal = false) to "NOT_LOCAL_TRACK",
            context(receiptMatches = false) to "TRACK_RECEIPT_CHANGED",
            context(uriMatches = false) to "TRACK_RECEIPT_CHANGED",
            context(scopeUnchanged = false) to "LIBRARY_SCOPE_CHANGED",
            context(revisionUnchanged = false) to "LIBRARY_ROOT_CHANGED",
            context(dialogOpen = false) to "DIALOG_CLOSED",
        )
        cases.forEach { (ctx, reason) ->
            assertFalse(policy.canLookup(ctx))
            assertEquals(reason, policy.denialReasonForLookup(ctx))
        }
    }

    @Test
    fun trackTransitionAndLibraryChangeRevokeTheLookup() {
        // Track A -> B: the receipt/URI of A no longer matches.
        assertFalse(policy.canLookup(context(receiptMatches = false)))
        // Same owner, different selected library scope.
        assertFalse(policy.canLookup(context(scopeUnchanged = false)))
        // Consent revoked by closing the dialog before dispatch.
        assertFalse(policy.canLookup(context(dialogOpen = false)))
        // The permit itself is a separate one-use token; the UI must not mint it.
        val authorization = MichiGenreLookupAuthorization()
        val intent = MichiGenreNormalizationPolicy.prepareSearch("Song", "Artist")!!
        val permit = authorization.issueSearch(intent)!!
        authorization.revoke()
        assertFalse(authorization.consumeSearch(permit, intent))
    }

    @Test
    fun mutationKeepsEveryStrictGuard() {
        assertTrue(policy.canMutateAudioFile(context()))
        val denied = mapOf(
            context(trackSelectedInPlayer = true) to "PLAYER_HOLDS_DESCRIPTOR",
            context(remoteSessionActive = true) to "REMOTE_SESSION_ACTIVE",
            context(handoffActive = true) to "PLAYBACK_HANDOFF_ACTIVE",
            context(localEndpoint = false) to "NOT_LOCAL_ENDPOINT",
            context(scopeUnchanged = false) to "LIBRARY_SCOPE_CHANGED",
            context(dialogOpen = false) to "DIALOG_CLOSED",
        )
        denied.forEach { (ctx, reason) ->
            assertFalse(policy.canMutateAudioFile(ctx))
            assertEquals(reason, policy.denialReasonForMutation(ctx))
        }
    }

    @Test
    fun writeIsNeverAuthorizedByRelaxingTheReadContract() {
        val readOnly = context(
            trackSelectedInPlayer = true, remoteSessionActive = true,
            handoffActive = true, localEndpoint = false,
        )
        assertTrue(policy.canLookup(readOnly))
        assertFalse(policy.canMutateAudioFile(readOnly))
    }
}
