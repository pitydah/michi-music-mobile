package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistBulkHeaderEntryPolicyTest {
    @Test fun noTapNeverReadsEvenWithReadyLibrary() = assertEquals(
        ArtistBulkHeaderEntry.IGNORE, decideArtistBulkHeaderEntry(0, "lib-a", true, true))
    @Test fun negativeTokenNeverReads() = assertEquals(
        ArtistBulkHeaderEntry.IGNORE, decideArtistBulkHeaderEntry(-1, "lib-a", true, true))
    @Test fun exactAuthorizedTapReadsOnlyLocally() = assertEquals(
        ArtistBulkHeaderEntry.READ_LOCAL, decideArtistBulkHeaderEntry(1, "lib-a", true, true))
    @Test fun repeatedHeaderTapMayOpenReview() = assertEquals(
        ArtistBulkHeaderEntry.READ_LOCAL, decideArtistBulkHeaderEntry(3, "lib-a", true, true))
    @Test fun allScopeCannotReadAsOneLibrary() = assertEquals(
        ArtistBulkHeaderEntry.SELECT_ONE_LIBRARY, decideArtistBulkHeaderEntry(1, null, false, true))
    @Test fun blankScopeCannotRead() = assertEquals(
        ArtistBulkHeaderEntry.SELECT_ONE_LIBRARY, decideArtistBulkHeaderEntry(1, "", true, true))
    @Test fun revokedAuthorityCannotRead() = assertEquals(
        ArtistBulkHeaderEntry.SELECT_ONE_LIBRARY, decideArtistBulkHeaderEntry(1, "lib-a", false, true))
    @Test fun unverifiedMembershipCannotRead() = assertEquals(
        ArtistBulkHeaderEntry.INVALID_MEMBERSHIP, decideArtistBulkHeaderEntry(1, "lib-a", true, false))
    @Test fun invalidMembershipDoesNotBecomeNetworkPermission() = assertEquals(
        ArtistBulkHeaderEntry.INVALID_MEMBERSHIP, decideArtistBulkHeaderEntry(9, "lib-a", true, false))
}
