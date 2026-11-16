package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class MichiAtlasGenrePolicyTest {
    private fun track(id: String, artist: String) =
        Track(id = id, title = id, artist = artist, genre = "Pop")

    @Test fun playbackGuardRejectsOldPagePendingScopeAndOutOfRangeTap() {
        val one = Track(id = "a", title = "One", artist = "Artist", genre = "Pop",
            sourceId = "local", filepath = "content://library/a")
        val pop = LibraryGenre("pop", "Pop", listOf(one), 1)
        val snapshot = atlasBuildPlaybackSnapshot("pop", listOf(pop), setOf("a"), emptySet())
        assertTrue(atlasMayDispatch(snapshot, "pop", 0, true))
        assertFalse(atlasMayDispatch(snapshot, "rock", 0, true))
        assertFalse(atlasMayDispatch(snapshot, "pop", -1, true))
        assertFalse(atlasMayDispatch(snapshot, "pop", 1, true))
        assertFalse(atlasMayDispatch(snapshot, "pop", null, true))
        assertFalse(atlasMayDispatch(snapshot, "pop", 0, false))
        assertFalse(atlasMayDispatch(null, "pop", 0, true))
    }

    @Test fun sharedCollisionIndexRejectsForeignOwnerOnEveryGenreVisited() {
        val origin = Track(id = "dup", title = "Same ID", genre = "Pop",
            sourceId = "a", filepath = "content://a/dup")
        val foreign = Track(id = "dup", title = "Different file", genre = "Synth-Pop",
            sourceId = "b", filepath = "content://b/dup")
        val safe = Track(id = "ok", title = "Allowed", genre = "Synth-Pop",
            sourceId = "b", filepath = "content://b/ok")
        val genres = listOf(
            LibraryGenre("pop", "Pop", listOf(origin), 1),
            LibraryGenre("synth-pop", "Synth-Pop", listOf(foreign, safe), 1),
        )
        val oncePerScope = atlasAmbiguousTrackIds(genres)
        assertEquals(setOf("dup"), oncePerScope)
        val first = atlasBuildPlaybackSnapshot("pop", genres, setOf("dup", "ok"),
            emptySet(), oncePerScope)
        val visited = atlasBuildPlaybackSnapshot("synth-pop", genres,
            setOf("dup", "ok"), emptySet(), oncePerScope)
        assertTrue(first.tracks.isEmpty())
        assertEquals(listOf("ok"), visited.tracks.map { it.id })
        assertEquals(listOf(null, 0), visited.allPreviews.map { it.playbackIndex })
        assertFalse(atlasMayDispatch(visited, "pop", 0, true))
        assertTrue(atlasMayDispatch(visited, "synth-pop", 0, true))
    }

    @Test fun atlasUsesTheExactCanonicalGenreIdentityAndAlreadyScopedTracks() {
        val genre = LibraryGenre("pop", "Pop", listOf(
            track("1", "a-ha"), track("2", "A-HA"), track("3", "Cock Robin")), 2)
        val snapshot = requireNotNull(atlasGenreSnapshot(genre))
        assertEquals("pop", snapshot.genreId)
        assertEquals("Pop", snapshot.title)
        assertEquals(3, snapshot.songCount)
        assertEquals(2, snapshot.albumCount)
        assertEquals(listOf("a-ha", "Cock Robin"), snapshot.artists)
    }


    @Test fun traversedPageReadsOnlyTheExactGenreFromTheCurrentScope() {
        val nw = LibraryGenre("new wave", "New Wave", listOf(track("nw", "Origin")), 1)
        val synth = LibraryGenre("synth-pop", "Synth-Pop", listOf(track("sp", "Target")), 1)
        val scope = listOf(nw, synth)
        assertEquals("Target", atlasGenreSnapshot(requireNotNull(
            atlasScopedGenre("synth-pop", scope)))?.artists?.single())
        assertNull(atlasScopedGenre("synth pop", scope))
        assertNull(atlasScopedGenre("k-pop", scope))
        assertNull(atlasScopedGenre("synth-pop", listOf(nw)))
        assertEquals(listOf("sp"), atlasScopedPlayableTracks(
            "synth-pop", scope, setOf("nw", "sp"), emptySet()).map { it.id })
        assertEquals(emptyList<Track>(), atlasScopedPlayableTracks(
            "synth-pop", scope, setOf("nw", "sp"), setOf("sp")))
    }

    @Test fun ambiguousScopedDuplicateGenreIdentityFailsClosed() {
        val a = LibraryGenre("pop", "Pop", listOf(track("a", "First")), 1)
        val b = LibraryGenre("pop", "Pop", listOf(track("b", "Second")), 1)
        assertNull(atlasScopedGenre("pop", listOf(a, b)))
        assertTrue(atlasScopedPlayableTracks("pop", listOf(a, b), setOf("a", "b"),
            emptySet()).isEmpty())
    }

    @Test fun unclassifiedOrMissingKeyCannotOpenAnAtlasGenre() {
        assertFalse(atlasGenreIsEligible(LibraryGenre("", "Vacío", emptyList(), 0)))
        assertNull(atlasGenreSnapshot(LibraryGenre("", "Vacío", emptyList(), 0)))
        val unclassified = groupLibraryGenres(listOf(Track(id = "x", title = "X", genre = ""))).single()
        assertTrue(genreIsUnclassified(unclassified.id))
        assertNull(atlasGenreSnapshot(unclassified))
    }

    @Test fun artistProjectionIsBoundedAndDoesNotTreatArtistsAsGenreInfluences() {
        val genre = LibraryGenre("pop", "Pop", (1..20).map { track("$it", "Band $it") }, 0)
        assertEquals(6, atlasGenreSnapshot(genre)?.artists?.size)
        assertTrue(atlasAcceptedRelations("pop", emptyList()).isEmpty())
    }

    @Test fun boundedNavigationRequiresPublishedDestinationAndValidDirectedProvenance() {
        val relation = AtlasVerifiedRelation(
            "new wave", "synth-pop", AtlasRelationKind.ASSOCIATED_WITH,
            listOf(AtlasSource("Rock Hall", "https://rockhall.com/inductees/duran-duran/")),
            "Synth-Pop", "Contexto documentado",
        )
        val root = listOf("new wave")
        val next = atlasAdvanceRoute(root, "new wave", relation, true)
        assertEquals(listOf("new wave", "synth-pop"), next)
        assertEquals(root, atlasPreviousRoute(requireNotNull(next)))
        assertNull(atlasPreviousRoute(root))
        assertNull(atlasAdvanceRoute(root, "pop", relation, true))
        assertNull(atlasAdvanceRoute(root, "new wave", relation.copy(sources = emptyList()), true))
        assertNull(atlasAdvanceRoute(root, "new wave", relation, false))
        assertNull(atlasAdvanceRoute(root, "new wave", relation.copy(toGenreId = "new wave"), true))
    }

    @Test fun relationCycleRewindsHistoryAndNeverGrowsWithoutLimit() {
        val linked = AtlasVerifiedRelation("synth-pop", "new wave",
            AtlasRelationKind.ASSOCIATED_WITH,
            listOf(AtlasSource("Fuente", "https://example.org/")),
        )
        val trail = listOf("new wave", "synth-pop")
        assertEquals(listOf("new wave"), atlasAdvanceRoute(trail, "synth-pop", linked, true))
        val max = listOf("root", "a", "b", "c", "d", "e", "f", "g")
        assertEquals(ATLAS_MAX_ROUTE_DEPTH, max.size)
        val over = AtlasVerifiedRelation("g", "h", AtlasRelationKind.ASSOCIATED_WITH,
            listOf(AtlasSource("Fuente", "https://example.org/")))
        assertNull(atlasAdvanceRoute(max, "g", over, true))
        assertEquals(max.dropLast(1), atlasPreviousRoute(max))
    }

    @Test fun historicalRelationsFailClosedWithoutSourcesAndRejectSelfEdges() {
        val valid = AtlasVerifiedRelation("pop", "synth pop", AtlasRelationKind.SUBGENRE,
            listOf(AtlasSource("Verified reference", "https://example.org/research")))
        val missingSource = valid.copy(toGenreId = "other", sources = emptyList())
        val self = valid.copy(toGenreId = "pop")
        val broken = valid.copy(toGenreId = "rock", sources = listOf(AtlasSource("", "wrong")))
        val malicious = valid.copy(toGenreId = "unsafe", sources =
            listOf(AtlasSource("Fake", "https://")))
        val wrongDirection = valid.copy(fromGenreId = "different")
        assertEquals(listOf(valid), atlasAcceptedRelations("pop",
            listOf(valid, missingSource, self, broken, malicious, wrongDirection, valid)))
    }

    @Test fun crossGenreSameIdDifferentOwnerIsBlockedWithoutPunishingRealMultiGenreTrack() {
        val origin = Track(id = "id42", title = "Origin", genre = "New Wave",
            sourceId = "library-A", filepath = "content://a/id42")
        val foreign = Track(id = "id42", title = "Other file", genre = "Synth-Pop",
            sourceId = "library-B", filepath = "content://b/id42")
        val a = LibraryGenre("new wave", "New Wave", listOf(origin), 1)
        val b = LibraryGenre("synth-pop", "Synth-Pop", listOf(foreign), 1)
        val colliding = listOf(a, b)
        assertEquals(setOf("id42"), atlasAmbiguousTrackIds(colliding))
        assertTrue(atlasScopedPlayableTracks("new wave", colliding, setOf("id42"), emptySet()).isEmpty())
        assertTrue(atlasScopedPlayableTracks("synth-pop", colliding, setOf("id42"), emptySet()).isEmpty())

        // One physical track carrying two verified genre memberships is NOT ambiguous.
        val sameOwner = listOf(a, b.copy(tracks = listOf(origin)))
        assertTrue(atlasAmbiguousTrackIds(sameOwner).isEmpty())
        assertEquals(listOf(origin), atlasScopedPlayableTracks("synth-pop", sameOwner,
            setOf("id42"), emptySet()))
    }

    @Test fun previewIndexesAreBoundToAuthorizedQueueAndDisabledRowsRemainVisible() {
        val a = Track(id = "a", title = "Blocked", genre = "Pop", sourceId = "local",
            filepath = "content://local/a")
        val b = Track(id = "b", title = "Playable", genre = "Pop", sourceId = "local",
            filepath = "content://local/b")
        val c = Track(id = "c", title = "Playable 2", genre = "Pop", sourceId = "local",
            filepath = "content://local/c")
        val genre = LibraryGenre("pop", "Pop", listOf(a, b, c), 3)
        val playable = atlasScopedPlayableTracks("pop", listOf(genre), setOf("a", "b", "c"),
            setOf("a"))
        assertEquals(listOf("b", "c"), playable.map { it.id })
        val preview = atlasTrackPreviews(genre, playable)
        assertEquals(listOf("a", "b", "c"), preview.map { it.track.id })
        assertEquals(listOf(null, 0, 1), preview.map { it.playbackIndex })
        assertEquals(emptyList<AtlasTrackPreview>(), atlasTrackPreviews(null, playable))
    }

    @Test fun previewIsBoundedAndDoesNotDuplicatePhysicalIdentity() {
        val items = (0..22).map { Track(id = "id$it", genre = "Pop", title = "Track $it") }
        val genre = LibraryGenre("pop", "Pop", listOf(items.first()) + items, 0)
        val preview = atlasTrackPreviews(genre, items)
        assertEquals(ATLAS_TRACK_PREVIEW_LIMIT, preview.size)
        assertEquals((0..7).map { "id$it" }, preview.map { it.track.id })
        assertTrue(preview.all { it.playbackIndex != null })
    }


    @Test fun availableOnlyFilterFindsSongsBeyondEightBlockedRows() {
        val tracks = (1..14).map { index ->
            Track(id = "track$index", title = "Track $index", artist = "Band",
                genre = "Pop", sourceId = "local", filepath = "content://local/$index")
        }
        val genre = LibraryGenre("pop", "Pop", tracks, 1)
        val playableIds = tracks.drop(10).map { it.id }.toSet()
        val snapshot = atlasBuildPlaybackSnapshot("pop", listOf(genre), playableIds, emptySet())
        assertEquals(14, genre.tracks.size)
        assertEquals((1..8).map { "track$it" }, snapshot.allPreviews.map { it.track.id })
        assertTrue(snapshot.allPreviews.all { it.playbackIndex == null })
        assertEquals((11..14).map { "track$it" },
            snapshot.playablePreviews.map { it.track.id })
        assertEquals(listOf(0, 1, 2, 3),
            snapshot.playablePreviews.map { it.playbackIndex })
        assertEquals(playableIds.size, snapshot.tracks.size)
    }

    @Test fun scopePlaybackProjectionKeepsOwnersAndPermissionsAcrossVisit() {
        val primary = Track(id = "dup", title = "Primary", artist = "A", genre = "New Wave",
            sourceId = "library-a", filepath = "content://a/dup")
        val foreign = Track(id = "dup", title = "Foreign", artist = "B", genre = "Synth-Pop",
            sourceId = "library-b", filepath = "content://b/dup")
        val unique = Track(id = "ok", title = "Allowed", artist = "C", genre = "Synth-Pop",
            sourceId = "library-b", filepath = "content://b/ok")
        val scopes = listOf(
            LibraryGenre("new wave", "New Wave", listOf(primary), 1),
            LibraryGenre("synth-pop", "Synth-Pop", listOf(foreign, unique), 2),
        )
        val p = atlasBuildPlaybackSnapshot("synth-pop", scopes, setOf("dup", "ok"), emptySet())
        assertEquals(setOf("dup"), p.ambiguousIds)
        assertEquals(listOf("ok"), p.tracks.map { it.id })
        assertEquals(listOf("ok"), p.playablePreviews.map { it.track.id })
        val forbidden = atlasBuildPlaybackSnapshot("synth-pop", scopes, setOf("dup", "ok"), setOf("ok"))
        assertTrue(forbidden.tracks.isEmpty())
        assertTrue(forbidden.playablePreviews.isEmpty())
        val external = atlasBuildPlaybackSnapshot("art rock", scopes, setOf("ok"), emptySet())
        assertTrue(external.tracks.isEmpty())
        assertTrue(external.allPreviews.isEmpty())
    }

    @Test fun breadcrumbsMayOnlyRewindToAnActualVisitedGenre() {
        val trail = listOf("new wave", "synth-pop", "darkwave")
        assertEquals(listOf("new wave", "synth-pop"), atlasRewindRoute(trail, "synth-pop"))
        assertEquals(listOf("new wave"), atlasRewindRoute(trail, "new wave"))
        assertEquals(trail, atlasRewindRoute(trail, "darkwave"))
        assertNull(atlasRewindRoute(trail, "art rock"))
        assertNull(atlasRewindRoute(emptyList(), "new wave"))
        assertNull(atlasRewindRoute((1..9).map { "g$it" }, "g1"))
    }

}
