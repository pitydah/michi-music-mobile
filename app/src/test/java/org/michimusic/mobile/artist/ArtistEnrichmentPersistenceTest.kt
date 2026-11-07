package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.data.artist.ArtistEnrichmentRecord
import org.michimusic.data.artist.ArtistAlbumCreditBindingRecord
import org.michimusic.data.artist.ArtistMembershipEvidenceRecord
import org.michimusic.data.artist.DurableArtistAssociationRecord
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 4.12.2 only ships up to SDK 34, while the app targets 35. Every
// other Robolectric suite in this repository pins SDK 33 for the same reason.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ArtistEnrichmentPersistenceTest {
    private lateinit var database: ArtistEnrichmentCacheDatabase
    private var now = 1_000_000L
    private lateinit var store: RoomArtistEnrichmentSelectionStore
    private val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val albums = listOf("album-b", "album-a")
    private val credit = "Patti Austin & James Ingram"

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ArtistEnrichmentCacheDatabase::class.java
        ).allowMainThreadQueries().build()
        store = RoomArtistEnrichmentSelectionStore(database) { now }
    }

    @After fun tearDown() { database.close() }

    private fun profile() = ArtistExternalProfile(mbid, credit, "", "Person", "US")

    private fun groupFor(vararg albumIds: String): String = requireNotNull(
        scopedArtistCreditKey("lib-a", credit, albumIds.toList()),
    ).second

    private suspend fun putHistorical(
        albumGroupKey: String,
        membershipFingerprint: String,
        identity: String,
        albumIdsJson: String,
    ) {
        val creditKey = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).first
        val saved = profile().copy(mbid = identity, name = "Historical $identity")
        database.artistEnrichmentDao().put(
            ArtistEnrichmentRecord(
                libraryId = "lib-a",
                creditKey = creditKey,
                albumGroupKey = albumGroupKey,
                confirmedMbid = identity,
                profileJson = Json.encodeToString(saved),
                savedAtMs = 1L,
                staleAfterMs = Long.MAX_VALUE,
                source = "historical-test",
                biographySourceUrl = null,
                portraitSourceUrl = null,
                portraitLicense = null,
                portraitLicenseUrl = null,
                portraitAttribution = null,
                albumIdsJson = albumIdsJson,
                membershipFingerprint = membershipFingerprint,
            ),
        )
        database.artistEnrichmentDao().putDurableAssociation(
            DurableArtistAssociationRecord(
                libraryId = "lib-a",
                creditKey = creditKey,
                associationId = albumGroupKey,
                identityAssociationId = albumGroupKey,
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        )
        val historicalAlbums = Json.decodeFromString<List<String>>(albumIdsJson)
        if (groupFor(*historicalAlbums.toTypedArray()) == albumGroupKey) {
            historicalAlbums.forEach { albumId ->
                database.artistEnrichmentDao().putAlbumCreditBinding(
                    ArtistAlbumCreditBindingRecord(
                        libraryId = "lib-a",
                        albumKey = albumId,
                        creditKey = creditKey,
                        associationId = albumGroupKey,
                        evidenceType = "HISTORICAL_TEST",
                        confirmedAtMs = 1L,
                    ),
                )
                database.artistEnrichmentDao().putMembershipEvidence(
                    ArtistMembershipEvidenceRecord(
                        libraryId = "lib-a",
                        albumKey = albumId,
                        creditKey = creditKey,
                        associationId = albumGroupKey,
                        evidenceType = "HISTORICAL_TEST",
                        evidenceKey = albumId,
                        status = "CONFIRMED",
                        source = "TEST",
                        observedAtMs = 1L,
                        confirmedAtMs = 1L,
                    ),
                )
            }
        }
    }

    @Test fun oneLibraryNeverAliasesAnother() = runBlocking {
        store.save("lib-a", credit, albums, profile())
        assertEquals(mbid, store.load("lib-a", credit, albums)?.profile?.mbid)
        assertNull(store.load("lib-b", credit, albums))
        val recovered = store.load("lib-a", credit, listOf("album-a"))
        assertEquals(mbid, recovered?.profile?.mbid)
        assertFalse(requireNotNull(recovered).needsConfirmation)
        assertNull(store.load("lib-a", credit, listOf("album-z")))
        assertEquals(mbid, store.load("lib-a", credit, albums.reversed())?.profile?.mbid)
    }

    @Test fun ambiguousHistoricalAlbumOverlapIsReportedWithoutDeletingRows() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        store.save("lib-a", credit, listOf("album-a"), profile())
        store.save(
            "lib-a", credit, listOf("album-b"),
            profile().copy(mbid = otherMbid, name = "Patti Austin"),
        )
        val failure = runCatching {
            store.load("lib-a", credit, listOf("album-a", "album-b"))
        }.exceptionOrNull()
        assertTrue(failure is ArtistAssociationAmbiguousException)
        assertEquals(
            2,
            database.artistEnrichmentDao().getAllForCredit(
                "lib-a",
                requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).first,
            ).size,
        )
    }

    @Test fun duplicateMembershipWithDifferentMbidsFailsClosedAndPreservesBothRows() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val current = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).second
        putHistorical(groupFor("album-a"), current, mbid, "[\"album-a\"]")
        putHistorical(groupFor("album-b"), current, otherMbid, "[\"album-b\"]")

        val failure = runCatching { store.load("lib-a", credit, albums) }.exceptionOrNull()

        assertNotNull("Competing membership identities must never use LIMIT 1", failure)
        assertTrue(failure?.message.orEmpty().contains("ambigua", ignoreCase = true))
        assertEquals(
            2,
            database.artistEnrichmentDao()
                .getAllForCredit("lib-a", requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).first)
                .size,
        )
    }

    @Test fun durableExplicitResolutionSelectsOneHistoricalAssociationWithoutDeletingTheOther() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
        val groupA = groupFor("album-a")
        val groupB = groupFor("album-b")
        putHistorical(groupA, keys.second, mbid, "[\"album-a\"]")
        putHistorical(groupB, keys.second, otherMbid, "[\"album-b\"]")
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artist_association_resolution (
                libraryId TEXT NOT NULL,
                creditKey TEXT NOT NULL,
                membershipFingerprint TEXT NOT NULL,
                chosenAlbumGroupKey TEXT NOT NULL,
                chosenMbid TEXT NOT NULL,
                evidence TEXT NOT NULL,
                confirmedAtMs INTEGER NOT NULL,
                PRIMARY KEY(libraryId, creditKey, membershipFingerprint)
            )
            """.trimIndent(),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO artist_association_resolution
                (libraryId, creditKey, membershipFingerprint, chosenAlbumGroupKey,
                 chosenMbid, evidence, confirmedAtMs)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>("lib-a", keys.first, keys.second, groupB, otherMbid, "USER_EXPLICIT_CHOICE", 7L),
        )

        val selected = requireNotNull(store.load("lib-a", credit, albums))
        val afterAlbumAddition = runCatching {
            store.load("lib-a", credit, albums + "album-c")
        }.exceptionOrNull()

        assertEquals(otherMbid, selected.profile.mbid)
        assertEquals(groupB, selected.associationGroupKey)
        assertTrue(
            "An exact A+B choice must not silently become an A+B+C choice",
            afterAlbumAddition is ArtistAssociationAmbiguousException,
        )
        assertEquals(
            setOf(groupA, groupB),
            database.artistEnrichmentDao().getAllForCredit("lib-a", keys.first)
                .map { it.albumGroupKey }.toSet(),
        )
    }

    @Test fun exactCombinedResolutionCannotStealTheOtherArtistsCanonicalSubset() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val albumA = listOf("album-a")
        val albumB = listOf("album-b")
        store.save("lib-a", credit, albumA, profile())
        store.save(
            "lib-a",
            credit,
            albumB,
            profile().copy(mbid = otherMbid, name = "Historical other"),
        )
        val groupA = requireNotNull(store.load("lib-a", credit, albumA)?.associationGroupKey)
        val groupB = requireNotNull(store.load("lib-a", credit, albumB)?.associationGroupKey)

        store.resolveAssociation("lib-a", credit, albumA + albumB, groupA, mbid)

        val exactCombined = requireNotNull(store.load("lib-a", credit, albumA + albumB))
        val subsetA = requireNotNull(store.load("lib-a", credit, albumA))
        val subsetB = requireNotNull(store.load("lib-a", credit, albumB))
        assertEquals(groupA, exactCombined.associationGroupKey)
        assertEquals(mbid, subsetA.profile.mbid)
        assertEquals(groupB, subsetB.associationGroupKey)
        assertEquals(otherMbid, subsetB.profile.mbid)

        val creditKey = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumA)).first
        val canonicalA = requireNotNull(database.artistEnrichmentDao().get("lib-a", creditKey, groupA))
        val canonicalB = requireNotNull(database.artistEnrichmentDao().get("lib-a", creditKey, groupB))
        assertEquals("[\"album-a\"]", canonicalA.albumIdsJson)
        assertEquals("[\"album-b\"]", canonicalB.albumIdsJson)
    }

    @Test fun unknownAlbumWithoutCanonicalEvidenceIsNeverAssignedByAnOldDecision() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val albumA = listOf("album-a")
        val albumB = listOf("album-b")
        store.save("lib-a", credit, albumA, profile())
        store.save("lib-a", credit, albumB, profile().copy(mbid = otherMbid, name = "Other"))
        val groupA = requireNotNull(store.load("lib-a", credit, albumA)?.associationGroupKey)
        store.resolveAssociation("lib-a", credit, albumA + albumB, groupA, mbid)

        assertNull(store.load("lib-a", credit, listOf("album-c")))
    }

    @Test fun profileRefreshAfterRecoveredAlbumAdditionKeepsOriginalCanonicalEvidence() = runBlocking {
        val albumA = listOf("album-a")
        store.save("lib-a", credit, albumA, profile())
        val original = requireNotNull(store.load("lib-a", credit, albumA))
        val group = requireNotNull(original.associationGroupKey)

        val expanded = albumA + "album-c"
        val recovered = requireNotNull(store.load("lib-a", credit, expanded))
        assertTrue(recovered.needsConfirmation)
        store.save("lib-a", credit, expanded, recovered.profile, group)

        val creditKey = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumA)).first
        val row = requireNotNull(database.artistEnrichmentDao().get("lib-a", creditKey, group))
        assertEquals("[\"album-a\"]", row.albumIdsJson)
        assertEquals(group, row.membershipFingerprint)
        assertTrue(requireNotNull(store.load("lib-a", credit, expanded)).needsConfirmation)
    }

    @Test fun broadenedLegacyEvidenceCannotOverrideAConflictingCanonicalSubset() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val albumA = listOf("album-a")
        val albumB = listOf("album-b")
        val combinedFingerprint = requireNotNull(
            scopedArtistCreditKey("lib-a", credit, albumA + albumB),
        ).second
        val groupA = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumA)).second
        val groupB = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumB)).second
        putHistorical(groupA, combinedFingerprint, mbid, "[\"album-a\",\"album-b\"]")
        putHistorical(groupB, groupB, otherMbid, "[\"album-b\"]")

        val subsetB = requireNotNull(store.load("lib-a", credit, albumB))

        assertEquals(groupB, subsetB.associationGroupKey)
        assertEquals(otherMbid, subsetB.profile.mbid)
    }

    @Test fun broadenedLegacyEvidenceWithAStaleMembershipCannotClaimANewSubset() = runBlocking {
        val albumA = listOf("album-a")
        val albumB = listOf("album-b")
        val groupA = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumA)).second
        putHistorical(groupA, groupA, mbid, "[\"album-a\",\"album-b\"]")

        assertNull(
            "Evidence that no longer hashes to its canonical group cannot claim album B",
            store.load("lib-a", credit, albumB),
        )
        assertNull(
            "Malformed legacy evidence remains insufficient even for a listed album",
            store.load("lib-a", credit, albumA),
        )
    }

    @Test fun explicitResolutionRejectsACandidateRemovedBeforeConfirmation() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
        val groupA = groupFor("album-a")
        val groupB = groupFor("album-b")
        putHistorical(groupA, keys.second, mbid, "[\"album-a\"]")
        putHistorical(groupB, keys.second, otherMbid, "[\"album-b\"]")
        assertEquals(2, store.ambiguousAssociations("lib-a", credit, albums).size)
        database.artistEnrichmentDao().delete("lib-a", keys.first, groupB)

        val failure = runCatching {
            store.resolveAssociation("lib-a", credit, albums, groupB, otherMbid)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(
            null,
            database.artistEnrichmentDao().getAssociationResolution("lib-a", keys.first, keys.second),
        )
        assertEquals(
            listOf(groupA),
            database.artistEnrichmentDao().getAllForCredit("lib-a", keys.first).map { it.albumGroupKey },
        )
    }

    @Test fun sameMbidInTwoHistoricalGroupsKeepsGroupScopedSelection() = runBlocking {
        val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
        val groupA = groupFor("album-a")
        val groupB = groupFor("album-b")
        putHistorical(groupA, keys.second, mbid, "[\"album-a\"]")
        putHistorical(groupB, keys.second, mbid, "[\"album-b\"]")

        store.resolveAssociation("lib-a", credit, albums, groupB, mbid)
        val selected = requireNotNull(store.load("lib-a", credit, albums))
        store.saveIdentity("lib-a", credit, albums, mbid, groupB)
        store.save("lib-a", credit, albums, profile(), groupB)
        val refreshed = requireNotNull(store.load("lib-a", credit, albums))
        val afterAlbumAddition = runCatching {
            store.load("lib-a", credit, albums + "album-c")
        }.exceptionOrNull()

        assertEquals(mbid, selected.profile.mbid)
        assertEquals(groupB, selected.associationGroupKey)
        assertEquals(groupB, refreshed.associationGroupKey)
        assertTrue(
            "A decision for the exact A+B view must not choose a group for A+B+C",
            afterAlbumAddition is ArtistAssociationAmbiguousException,
        )
        assertEquals(
            setOf(groupA, groupB),
            database.artistEnrichmentDao().getAllForCredit("lib-a", keys.first)
                .map { it.albumGroupKey }.toSet(),
        )
    }

    @Test fun insufficientExactLegacyRowCannotOverrideAConfirmedBinding() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val current = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).second
        putHistorical(current, "legacy-membership", mbid, "[\"album-a\"]")
        putHistorical(groupFor("album-b"), current, otherMbid, "[\"album-b\"]")

        val loaded = requireNotNull(store.load("lib-a", credit, albums))

        assertEquals(otherMbid, loaded.profile.mbid)
        assertEquals(groupFor("album-b"), loaded.associationGroupKey)
        assertTrue("Album A remains unbound and must not be claimed", loaded.needsConfirmation)
    }

    @Test fun aDifferentMbidCannotSilentlyReplaceAnExistingAssociation() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        store.save("lib-a", credit, albums, profile())

        val failure = runCatching {
            store.save(
                "lib-a",
                credit,
                albums,
                profile().copy(mbid = otherMbid, name = "Another Artist"),
            )
        }.exceptionOrNull()

        assertNotNull("Replacing a confirmed MBID requires explicit unlink/clear", failure)
        assertEquals(mbid, store.load("lib-a", credit, albums)?.profile?.mbid)
    }

    @Test fun expiryMarksFactsStaleButPreservesOfflineDetails() = runBlocking {
        store.save("lib-a", credit, albums, profile())
        assertFalse(requireNotNull(store.load("lib-a", credit, albums)).stale)
        now += 8L * 24 * 60 * 60 * 1000
        val cached = store.load("lib-a", credit, albums)
        assertNotNull(cached)
        assertTrue(requireNotNull(cached).stale)
        assertEquals(mbid, cached.profile.mbid)
    }

    @Test fun clearIsScopedAndDoesNotRemoveOtherLibrary() = runBlocking {
        store.save("lib-a", credit, albums, profile())
        store.save("lib-b", credit, albums, profile())
        store.clear("lib-a", credit, albums)
        assertNull(store.load("lib-a", credit, albums))
        assertNotNull(store.load("lib-b", credit, albums))
    }

    @Test fun clearInvalidatesOnlyResolutionsThatDependOnTheDeletedGroup() = runBlocking {
        val secondMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val thirdMbid = "0f9fdb47-084b-4f28-b105-5c5ca6b9e597"
        val albumA = listOf("album-a")
        val albumB = listOf("album-b")
        val albumC = listOf("album-c")
        val groupA = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumA)).second
        val groupB = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumB)).second
        val groupC = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumC)).second
        val combinedAB = requireNotNull(
            scopedArtistCreditKey("lib-a", credit, albumA + albumB),
        ).second
        val combinedBC = requireNotNull(
            scopedArtistCreditKey("lib-a", credit, albumB + albumC),
        ).second
        val creditKey = requireNotNull(scopedArtistCreditKey("lib-a", credit, albumA)).first
        store.save("lib-a", credit, albumA, profile())
        store.save(
            "lib-a", credit, albumB,
            profile().copy(mbid = secondMbid, name = "Second historical artist"),
        )
        store.save(
            "lib-a", credit, albumC,
            profile().copy(mbid = thirdMbid, name = "Third historical artist"),
        )
        store.resolveAssociation("lib-a", credit, albumA + albumB, groupA, mbid)
        store.resolveAssociation("lib-a", credit, albumB + albumC, groupB, secondMbid)

        store.clear("lib-a", credit, albumA, groupA)

        assertNull(database.artistEnrichmentDao().get("lib-a", creditKey, groupA))
        assertNotNull(database.artistEnrichmentDao().get("lib-a", creditKey, groupB))
        assertNotNull(database.artistEnrichmentDao().get("lib-a", creditKey, groupC))
        assertNull(
            database.artistEnrichmentDao().getAssociationResolution(
                "lib-a", creditKey, combinedAB,
            ),
        )
        assertEquals(
            groupB,
            database.artistEnrichmentDao().getAssociationResolution(
                "lib-a", creditKey, combinedBC,
            )?.chosenAlbumGroupKey,
        )
    }

    @Test fun corruptJsonIsReportedAsACacheFailureInsteadOfRenderedAsConfirmed() = runBlocking {
        val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
        store.confirmNewIdentity("lib-a", credit, albums, mbid)
        val valid = requireNotNull(database.artistEnrichmentDao().get("lib-a", keys.first, keys.second))
        database.artistEnrichmentDao().put(valid.copy(profileJson = "{not-json"))
        val failure = try {
            store.load("lib-a", credit, albums)
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull(failure)
        assertTrue(requireNotNull(failure).message.orEmpty().contains("corrupto"))
    }

    @Test fun optionalProviderOutcomesSurviveTheRoundTrip() = runBlocking {
        val saved = profile().copy(
            biography = "Banda australiana de rock.",
            biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
            biographyOutcome = ArtistMetadataOutcome.SUCCESS,
            portraitOutcome = ArtistMetadataOutcome.NO_ALLOWED_LICENSE,
            wikidataQid = "Q123",
        )
        store.save("lib-a", credit, albums, saved)
        val loaded = requireNotNull(store.load("lib-a", credit, albums)).profile
        assertEquals(saved, loaded)
        assertEquals(ArtistMetadataOutcome.SUCCESS, loaded.biographyOutcome)
        assertEquals(ArtistMetadataOutcome.NO_ALLOWED_LICENSE, loaded.portraitOutcome)
        assertEquals("Q123", loaded.wikidataQid)
    }

    @Test fun noBindingInAllScopeAndCreditNeverSplitsOnAmpersand() {
        assertNull(scopedArtistCreditKey(null, credit, albums))
        assertNull(scopedArtistCreditKey("SCOPE_ALL", credit, albums))
        assertEquals("patti austin & james ingram", scopedArtistCreditKey("lib-a", credit, albums)?.first)
        assertNull(scopedArtistCreditKey("lib-a", credit, emptyList()))
    }
}
