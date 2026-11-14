package org.michimusic.core.intelligence

import org.junit.Assert.*
import org.junit.Test

class MichiGenreLookupAuthorizationTest {
    private val p = MichiGenreNormalizationPolicy
    private val id = "ae57a2bc-4de1-4e94-9f07-b5301eacdd91"
    private val other = "4cb3d02a-242b-4a42-b497-f3ee5d8a552c"
    private val search = p.prepareSearch("Song", "Artist", "Album")!!

    @Test fun searchPermissionIsBoundToExactFieldsAndCannotBeReplayed() {
        val ledger = MichiGenreLookupAuthorization()
        val token = ledger.issueSearch(search)
        assertTrue(ledger.consumeSearch(token, search))
        assertFalse(ledger.consumeSearch(token, search))
    }

    @Test fun modifiedSearchConsumesAndRejectsOriginalTicket() {
        val ledger = MichiGenreLookupAuthorization()
        val token = ledger.issueSearch(search)
        assertFalse(ledger.consumeSearch(token, search.copy(album = "Other")))
        assertFalse(ledger.consumeSearch(token, search))
    }

    @Test fun malformedSearchCannotIssuePermission() {
        val ledger = MichiGenreLookupAuthorization()
        assertNull(ledger.issueSearch(search.copy(title = " Song ")))
        assertFalse(ledger.consumeSearch(null, search))
    }

    @Test fun permissionIsNotTransferableAcrossUiOwners() {
        val a = MichiGenreLookupAuthorization()
        val b = MichiGenreLookupAuthorization()
        val token = a.issueSearch(search)
        assertFalse(b.consumeSearch(token, search))
        assertTrue(a.consumeSearch(token, search))
    }

    @Test fun secondPermitSupersedesEarlierOne() {
        val ledger = MichiGenreLookupAuthorization()
        val first = ledger.issueSearch(search)
        val second = ledger.issueSearch(search)
        assertFalse(ledger.consumeSearch(first, search))
        assertFalse(ledger.consumeSearch(second, search))
        assertTrue(ledger.consumeSearch(ledger.issueSearch(search), search))
    }

    @Test fun recordingPermitIsDistinctFromSearchPermit() {
        val ledger = MichiGenreLookupAuthorization()
        val searchGrant = ledger.issueSearch(search)
        assertFalse(ledger.consumeRecording(searchGrant, id))
        val lookup = ledger.issueRecording(id)
        assertTrue(ledger.consumeRecording(lookup, id))
        assertFalse(ledger.consumeRecording(lookup, id))
    }

    @Test fun mbidIsCanonicalButCannotBeSwapped() {
        val ledger = MichiGenreLookupAuthorization()
        val grant = ledger.issueRecording(id.uppercase())
        assertFalse(ledger.consumeRecording(grant, other))
        assertTrue(ledger.consumeRecording(ledger.issueRecording(id.uppercase()), id))
    }

    @Test fun cancellationRevokesConsentWithoutNetwork() {
        val ledger = MichiGenreLookupAuthorization()
        val grant = ledger.issueSearch(search)
        ledger.revoke()
        assertFalse(ledger.consumeSearch(grant, search))
        assertFalse(ledger.consumeRecording(null, id))
    }
}
