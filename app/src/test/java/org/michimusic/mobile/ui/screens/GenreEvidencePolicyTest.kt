package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class GenreEvidencePolicyTest {
    private fun track(owner: String = "", genre: String = "", path: String = ""): Track =
        Track(id = "same", title = "Same", artist = "Band", album = "Record",
            duration = 123_000L, sourceId = owner, filepath = path, genre = genre)

    private fun evidence(owner: String, genre: String, path: String = ""): GenreOwnerEvidence =
        GenreOwnerEvidence(owner, "same", "Same", "Band", "Record", 123_000L, path, genre)

    @Test fun structuredGenreValuesRequireOwnerLocationAndMatchingTrackMetadata() {
        val a = track(owner = "saf-a", genre = "Jazz; Fusion", path = "content://tree/a.flac")
        val b = evidence("saf-a", "Jazz; Fusion", path = "content://tree/a.flac")
            .copy(genreValues = "Jazz; Fusion")
        val good = buildGenreScopedIndex(listOf(a), listOf(b), "saf-a", isAllScope = false)
        assertEquals(listOf("Jazz; Fusion"), good.genres.map { it.title })
        val badOwner = buildGenreScopedIndex(listOf(a), listOf(b.copy(sourceId = "saf-b")),
            "saf-a", isAllScope = false)
        assertEquals(listOf("Jazz; Fusion"), badOwner.genres.map { it.title })
        val badPath = buildGenreScopedIndex(listOf(a), listOf(b.copy(filepath = "content://tree/b.flac")),
            "saf-a", isAllScope = false)
        assertEquals(listOf("Jazz; Fusion"), badPath.genres.map { it.title })
    }

    @Test fun malformedOrContradictoryStructuredEvidenceCannotCreateExtraMembership() {
        val track = track(owner = "saf-a", genre = "Jazz; Fusion", path = "content://tree/a.flac")
        val correct = evidence("saf-a", "Jazz; Fusion", path = "content://tree/a.flac")
        val poison = correct.copy(genreValues = "Metal\u0000Fusion")
        val result = buildGenreScopedIndex(listOf(track), listOf(poison), "saf-a", isAllScope = false)
        assertEquals(listOf("Jazz; Fusion"), result.genres.map { it.title })
        val fake33 = (1..33).joinToString("\u0000") { "Style $it" }
        val many = buildGenreScopedIndex(listOf(track), listOf(correct.copy(genreValues = fake33)),
            "saf-a", isAllScope = false)
        assertEquals(listOf("Jazz; Fusion"), many.genres.map { it.title })
    }

    @Test fun ownerCollisionNeverTransfersStructuredGenreValues() {
        val song = track(owner = "saf-a", genre = "Jazz; Fusion", path = "content://tree/a.flac")
        val row = evidence("saf-a", "Jazz; Fusion", "content://tree/a.flac")
            .copy(genreValues = "Jazz; Fusion")
        val other = evidence("saf-b", "Rock", "content://tree/b.flac")
        val out = buildGenreScopedIndex(listOf(song), listOf(row, other), "saf-a", isAllScope = false)
        assertEquals(listOf("Jazz; Fusion"), out.genres.map { it.title })
        assertEquals(setOf("same"), out.blockedTrackIds)
    }

    @Test fun onePeerCanSupplyMissingGenreWithVerifiedOwnerEvenBeforeDownload() {
        val result = enrichGenreTracks(listOf(track()), listOf(evidence("peer-a", "Jazz")),
            scopeOwnerId = "peer-a", isAllScope = false, collidingOwnerIds = emptySet())
        assertEquals("Jazz", result.single().genre)
    }

    @Test fun ownerMismatchNeverTransfersAnotherPeersGenre() {
        val result = enrichGenreTracks(listOf(track()), listOf(evidence("peer-b", "Metal")),
            scopeOwnerId = "peer-a", isAllScope = false, collidingOwnerIds = emptySet())
        assertEquals("", result.single().genre)
    }

    @Test fun allLibrariesRefusesCollidingIdsEvenIfOnlyOneOwnerHasGenre() {
        val result = enrichGenreTracks(listOf(track()), listOf(evidence("peer-a", "Jazz")),
            scopeOwnerId = null, isAllScope = true, collidingOwnerIds = setOf("same"))
        assertEquals("", result.single().genre)
    }

    @Test fun allLibrariesRequiresMatchingPhysicalUriForUnownedNormalizedTrack() {
        val ok = enrichGenreTracks(listOf(track(path = "content://one")),
            listOf(evidence("safroot-a", "Folk", "content://one")),
            scopeOwnerId = null, isAllScope = true, collidingOwnerIds = emptySet())
        assertEquals("Folk", ok.single().genre)
        val blocked = enrichGenreTracks(listOf(track()), listOf(evidence("peer-a", "Jazz")),
            scopeOwnerId = null, isAllScope = true, collidingOwnerIds = emptySet())
        assertEquals("", blocked.single().genre)
    }

    @Test fun metadataMismatchAndDuplicateEvidenceFailClosed() {
        val mismatch = evidence("peer-a", "Rock").copy(title = "Different")
        assertEquals("", enrichGenreTracks(listOf(track()), listOf(mismatch),
            "peer-a", false, emptySet()).single().genre)
        assertEquals("", enrichGenreTracks(listOf(track()), listOf(evidence("peer-a", "Rock"),
            evidence("peer-a", "Jazz")), "peer-a", false, emptySet()).single().genre)
    }

    @Test fun syncedPeerDurationInSecondsIsCompatibleWithNormalizedMilliseconds() {
        val peer = evidence("peer-a", "Jazz").copy(duration = 123L)
        assertEquals("Jazz", enrichGenreTracks(listOf(track()), listOf(peer),
            "peer-a", false, emptySet()).single().genre)
        val invalid = evidence("peer-a", "Jazz").copy(duration = 30L)
        assertEquals("", enrichGenreTracks(listOf(track()), listOf(invalid),
            "peer-a", false, emptySet()).single().genre)
    }

    @Test fun existingTrackGenreRemainsAuthoritativeAndOwnerIsUnchanged() {
        val input = track(owner = "one", genre = "Blues")
        val projected = enrichGenreTracks(listOf(input), listOf(evidence("one", "Jazz")),
            "one", false, emptySet()).single()
        assertEquals(input, projected)
    }

    @Test fun collisionAcrossDifferentGenresBlocksBothQueues() {
        val jazz = track(owner = "peer-a", genre = "Jazz", path = "a.flac")
        val rock = track(owner = "peer-b", genre = "Rock", path = "b.flac")
        val blocked = unsafeGenrePlaybackIds(listOf(jazz, rock), emptySet())
        assertEquals(setOf("same"), blocked)
        assertTrue(canonicalGenrePlayableTracks(listOf(jazz), setOf("same"), blocked).isEmpty())
        assertTrue(canonicalGenrePlayableTracks(listOf(rock), setOf("same"), blocked).isEmpty())
    }

    @Test fun remoteAndSafScopesDoNotInheritLocalMediaStoreFailure() {
        assertEquals(false, shouldShowGenreMediaStoreFailure(false, "peer-library", "lib_device_local"))
        assertEquals(false, shouldShowGenreMediaStoreFailure(false, "saf-library", "lib_device_local"))
        assertEquals(false, shouldShowGenreMediaStoreFailure(false, null, "lib_device_local"))
    }

    @Test fun allAndDeviceScopesRetainTruthfulLocalMediaError() {
        assertTrue(shouldShowGenreMediaStoreFailure(true, null, "lib_device_local"))
        assertTrue(shouldShowGenreMediaStoreFailure(false, "lib_device_local", "lib_device_local"))
    }

    @Test fun twentyThousandTracksKeepStableGroupsAndNoSpuriousCollisions() {
        val items = (0 until 20_000).map { i ->
            Track(id = "id-$i", title = "Song $i", genre = if (i % 2 == 0) "Jazz" else "Rock",
                sourceId = "peer-a", filepath = "content://music/$i")
        }
        val projected = enrichGenreTracks(items, emptyList(), "peer-a", false, emptySet())
        assertEquals(2, groupLibraryGenres(projected).size)
        assertEquals(20_000, groupLibraryGenres(projected).sumOf { it.tracks.size })
        assertTrue(unsafeGenrePlaybackIds(projected, emptySet()).isEmpty())
    }

    @Test fun singleCachedMultiOwnerIdBlocksEvenIfNormalizedCatalogCollapsedIt() {
        val single = track(owner = "peer-a", genre = "Jazz")
        val blocked = unsafeGenrePlaybackIds(listOf(single), setOf("same"))
        assertTrue(canonicalGenrePlayableTracks(listOf(single), setOf("same"), blocked).isEmpty())
    }

    @Test fun cacheCollisionIncludesOwnerWithEmptyGenre() {
        val rows = listOf(evidence("peer-a", "Jazz"), evidence("peer-b", ""))
        assertEquals(setOf("same"), cachedGenreOwnerCollisions(rows))
    }

    @Test fun sameOwnerRepeatedEvidenceIsNotAPhantomOwnerCollision() {
        val rows = listOf(evidence("peer-a", "Jazz"), evidence("peer-a", "Blues"))
        assertTrue(cachedGenreOwnerCollisions(rows).isEmpty())
    }

    @Test fun oneScopeNeverEnrichesCollapsedIdentityWithDuplicateCachedOwners() {
        val rows = listOf(evidence("peer-a", "Jazz"), evidence("peer-b", "Metal"))
        val index = buildGenreScopedIndex(listOf(track()), rows, "peer-a", isAllScope = false)
        assertEquals("Sin clasificar", index.genres.single().title)
        assertEquals(setOf("same"), index.blockedTrackIds)
    }

    @Test fun oneScopeUniqueOwnerStillRecoversGenreWithoutUri() {
        val index = buildGenreScopedIndex(listOf(track()),
            listOf(evidence("peer-a", "Jazz")), "peer-a", isAllScope = false)
        assertEquals("Jazz", index.genres.single().title)
        assertTrue(index.blockedTrackIds.isEmpty())
    }

    @Test fun irrelevantCacheOwnersCannotInjectGenresIntoAnotherScope() {
        val scoped = track().copy(id = "here", title = "Here", genre = "Folk")
        val unrelated = (0 until 2000).map { n ->
            evidence("other-$n", "Metal").copy(id = "foreign-$n")
        }
        val index = buildGenreScopedIndex(listOf(scoped), unrelated,
            scopeOwnerId = "peer-a", isAllScope = false)
        assertEquals(listOf("Folk"), index.genres.map { it.title })
        assertEquals(listOf("here"), index.genres.single().tracks.map { it.id })
    }

    @Test fun joinedIndexNeverImportsAnUnscopedCachedTrack() {
        val onlyScoped = track().copy(id = "only", title = "Scoped", genre = "Folk")
        val index = buildGenreScopedIndex(listOf(onlyScoped), listOf(evidence("peer-a", "Jazz")),
            "peer-a", isAllScope = false)
        assertEquals(listOf("only"), index.genres.flatMap { it.tracks }.map { it.id })
        assertEquals("Folk", index.genres.single().title)
    }

    @Test fun allScopeMatchingUriCanReadUniqueOwnerGenre() {
        val index = buildGenreScopedIndex(listOf(track(path = "content://one")),
            listOf(evidence("safroot-a", "Folk", "content://one")),
            scopeOwnerId = null, isAllScope = true)
        assertEquals("Folk", index.genres.single().title)
    }

    @Test fun indexDoesNotExposePlaybackWhenCachedRowsCollideAcrossGenres() {
        val track = track(owner = "peer-a", path = "one.flac")
        val index = buildGenreScopedIndex(listOf(track),
            listOf(evidence("peer-a", "Jazz", "one.flac"),
                evidence("peer-b", "Metal", "other.flac")), "peer-a", isAllScope = false)
        assertEquals("Sin clasificar", index.genres.single().title)
        assertTrue(canonicalGenrePlayableTracks(index.genres.single().tracks,
            setOf("same"), index.blockedTrackIds).isEmpty())
    }
}
