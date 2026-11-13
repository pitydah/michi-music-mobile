package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class GenreGroupingPolicyTest {
    private fun song(id: String, genre: String, album: String = "Disco") =
        Track(id = id, title = id, genre = genre, album = album, artist = "Banda")

    @Test fun groupsMixedCaseAndMultivalueWithoutSplittingSlashes() {
        val genres = groupLibraryGenres(listOf(
            song("a", "Jazz; Rock/Pop").copy(genreValues = listOf("Jazz", "Rock/Pop")),
            song("b", "jazz"), song("c", "Rock/Pop")))
        assertEquals(listOf("Jazz", "Rock/Pop"), genres.map { it.title })
        assertEquals(listOf(2, 2), genres.map { it.tracks.size })
    }

    @Test fun excessiveLegacyGenreDelimitersAreRejectedBeforeSplitting() {
        val grouped = groupLibraryGenres(listOf(song("oversized", "Rock" + ";".repeat(50_000))))
        assertEquals(listOf("Sin clasificar"), grouped.map { it.title })
    }

    @Test fun originalPopRockId3TagProducesOneMembershipNotArtworkInference() {
        val song = song("local_777", "Pop rock")
            .copy(sourceId = "", genreValues = listOf("Pop rock"))
        val result = groupLibraryGenres(listOf(song))
        assertEquals(listOf("Pop rock"), result.map { it.title })
        assertEquals(listOf("local_777"), result.single().tracks.map { it.id })
        assertEquals(1, result.single().tracks.size)
        assertEquals(2, GenreViewMode.entries.size)
    }

    @Test fun unverifiedLegacySemicolonRemainsOneLiteralGenre() {
        val result = groupLibraryGenres(listOf(song("ambiguous", "Jazz; Fusion")))
        assertEquals(listOf("Jazz; Fusion"), result.map { it.title })
        assertEquals(listOf("ambiguous"), result.single().tracks.map { it.id })
        assertEquals(listOf("R&B/Soul"),
            groupLibraryGenres(listOf(song("slash", "R&B/Soul"))).map { it.title })
        assertEquals(listOf("Pop", "Rock"),
            groupLibraryGenres(listOf(song("delimited", "Rock\u0000Pop"))).map { it.title })
    }

    @Test fun structuredLiteralSemicolonDoesNotInventTwoGenres() {
        val literal = song("literal", "Jazz; Fusion").copy(genreValues = listOf("Jazz; Fusion"))
        val grouped = groupLibraryGenres(listOf(literal))
        assertEquals(listOf("Jazz; Fusion"), grouped.map { it.title })
        assertEquals(listOf("literal"), grouped.single().tracks.map { it.id })
    }

    @Test fun structuredSeparateValuesProduceSeparateGenresWithoutSplittingSlash() {
        val separated = song("multi", "Jazz; Rock/Pop")
            .copy(genreValues = listOf("Jazz", "Rock/Pop"))
        assertEquals(listOf("Jazz", "Rock/Pop"),
            groupLibraryGenres(listOf(separated)).map { it.title })
    }

    @Test fun providerUnknownGenreLabelsStayUnclassified() {
        val groups = groupLibraryGenres(listOf(
            song("u1", "Unclassified"), song("u2", "Unknown Genre"), song("u3", "None")))
        assertEquals(listOf("Sin clasificar"), groups.map { it.title })
        assertEquals(3, groups.single().tracks.size)
    }

    @Test fun untaggedSongsAreNotInferredAndSortLast() {
        val genres = groupLibraryGenres(listOf(song("a", ""), song("b", "<unknown>"), song("c", "Blues")))
        assertEquals(listOf("Blues", "Sin clasificar"), genres.map { it.title })
        assertEquals(2, genres.last().tracks.size)
    }

    @Test fun songsWithoutAlbumMetadataDoNotInventAnAlbum() {
        val noAlbum = Track(id = "single", title = "Single", genre = "Jazz", album = "")
        assertEquals(0, groupLibraryGenres(listOf(noAlbum)).single().albumCount)
        val explicitAlbumId = noAlbum.copy(albumId = "album-1")
        assertEquals(1, groupLibraryGenres(listOf(explicitAlbumId)).single().albumCount)
    }

    @Test fun duplicateAlbumMembershipDoesNotDuplicateTracks() {
        val item = song("a", "Rock")
        val groups = groupLibraryGenres(listOf(item, item))
        assertEquals(1, groups.single().tracks.size)
        assertEquals(1, groups.single().albumCount)
    }

    @Test fun emptyLibraryProducesNoSyntheticGenres() {
        assertTrue(groupLibraryGenres(emptyList()).isEmpty())
    }

    @Test fun featuredOrderUsesTrackCountNotListeningHistory() {
        val input = groupLibraryGenres(listOf(song("a", "Jazz"), song("b", "Rock"), song("c", "Rock")))
        assertEquals(listOf("Rock", "Jazz"), genresByTrackCount(input).map { it.title })
    }

    @Test fun onlyTwoApprovedGenreModesExist() {
        assertEquals(
            listOf("Mosaico destacado", "Editorial"),
            GenreViewMode.entries.map { it.displayName },
        )
    }

    @Test fun editorialLayoutDoesNotDependOnAnyAssetOrGenreName() {
        assertEquals(listOf(2, 1, 1, 2, 1, 1),
            (0..5).map { editorialSlot(it, 2).span })
        assertEquals(listOf(192, 246, 246, 192),
            (0..3).map { editorialSlot(it, 2).heightDp })
        assertEquals((0..5).map { 1 }, (0..5).map { editorialSlot(it, 1).span })
        assertEquals(200, editorialSlot(1, 1).heightDp)
    }

    @Test fun editorialTreatmentsAreThreeDistinctSemanticsIndependentOfImages() {
        assertEquals(
            listOf(GenreEditorialTreatment.COVER, GenreEditorialTreatment.PORTRAIT_LEFT,
                GenreEditorialTreatment.PORTRAIT_RIGHT, GenreEditorialTreatment.COVER),
            (0..3).map { editorialSlot(it, 2).treatment },
        )
        assertEquals(1, editorialSlot(2, 1).span)
    }

    @Test fun accentInsensitiveSearchStaysWithinGivenLibrary() {
        val genres = genresByTrackCount(groupLibraryGenres(listOf(
            song("a", "Clásica"), song("b", "Electronica"),
            song("c", "New Wave"), song("d", "R&B/Soul"))))
        assertEquals(listOf("Clásica"), filterLibraryGenres(genres, "clasica").map { it.title })
        assertEquals(listOf("Electronica"), filterLibraryGenres(genres, "ELÉCTRONICA").map { it.title })
        assertEquals(listOf("New Wave"), filterLibraryGenres(genres, "  new   wave ").map { it.title })
        assertEquals(listOf("R&B/Soul"), filterLibraryGenres(genres, "R&B/").map { it.title })
        assertEquals(genres, filterLibraryGenres(genres, "   "))
        assertTrue(filterLibraryGenres(genres, "no existe").isEmpty())
    }

    @Test fun searchIsNotShownForSmallLibrariesUnlessQueryActive() {
        assertTrue(showGenreSearch(31, ""))
        assertTrue(showGenreSearch(2, "jazz"))
        assertEquals(false, showGenreSearch(2, ""))
        assertEquals(false, showGenreSearch(0, ""))
    }

    @Test fun geometryReservesRealCardWidthAndLargeTextSpace() {
        assertEquals(1, genreGridColumns(320, 1f))
        assertEquals(1, genreGridColumns(360, 1f))
        assertEquals(1, genreGridColumns(389, 1f))
        assertEquals(2, genreGridColumns(390, 1f))
        assertEquals(2, genreGridColumns(411, 1f))
        assertEquals(2, genreGridColumns(600, 1.5f))
        assertEquals(1, genreGridColumns(600, 2f))
        assertEquals(218, genreMosaicHeightDp(true, 1f))
        assertEquals(166, genreMosaicHeightDp(false, 1f))
        assertEquals(358, genreMosaicHeightDp(true, 2f))
        assertEquals(291, genreMosaicHeightDp(false, 2f))
        assertEquals(352, genreEditorialHeightDp(editorialSlot(0, 2), 2f))
        assertEquals(406, genreEditorialHeightDp(editorialSlot(1, 2), 2f))
    }

    @Test fun compilationGuestsDoNotInflateRealAlbumCount() {
        val a = Track("a", "Canción A", artist="Invitada", album="Disco", albumArtist="Banda",
            albumId="album_31", genre="Jazz", sourceId="one")
        val b = Track("b", "Canción B", artist="Invitado", album="Disco", albumArtist="Banda",
            albumId="album_31", genre="Jazz", sourceId="one")
        val c = Track("c", "Canción C", artist="Invitado", album="Disco", albumArtist="Banda",
            albumId="album_31", genre="Jazz", sourceId="two")
        assertEquals(2, groupLibraryGenres(listOf(a,b,c)).single().albumCount)
    }

    @Test fun albumArtistFallbackGroupsGuestsWithoutAlbumId() {
        val a = Track("a", "A", album="Disco", artist="Invitada", albumArtist="Banda", genre="Rock")
        val b = Track("b", "B", album="Disco", artist="Invitado", albumArtist="Banda", genre="Rock")
        assertEquals(1, groupLibraryGenres(listOf(a,b)).single().albumCount)
    }

    @Test fun playableGenreQueueFailsClosedForAmbiguousIdAcrossOwners() {
        val a = Track("same", "A", genre="Jazz", sourceId="one", filepath="one.wav")
        val b = Track("same", "B", genre="Jazz", sourceId="two", filepath="two.wav")
        val c = Track("unique", "C", genre="Jazz", sourceId="one", filepath="three.wav")
        assertEquals(listOf("unique"),
            canonicalGenrePlayableTracks(listOf(a, b, c), setOf("same", "unique")).map { it.id })
        assertTrue(canonicalGenrePlayableTracks(listOf(a, b), setOf("same")).isEmpty())
    }

    @Test fun playableGenreQueueRetainsUniqueAuthorizedTracksInOrder() {
        val tracks = listOf(song("a", "Rock"), song("b", "Rock"), song("c", "Rock"))
        assertEquals(listOf("a", "c"), canonicalGenrePlayableTracks(tracks, setOf("a", "c")).map { it.id })
    }

    @Test fun currentPlayingIdentityHonorsOwnerAndKnownLocation() {
        val a = Track("same", "A", sourceId="one", filepath="music/a.flac")
        val b = Track("same", "B", sourceId="two", filepath="music/a.flac")
        val c = Track("same", "C", sourceId="one", filepath="music/c.flac")
        assertTrue(genrePlaybackIdentityMatches(a,a))
        assertEquals(false, genrePlaybackIdentityMatches(a,b))
        assertEquals(false, genrePlaybackIdentityMatches(a,c))
        assertEquals(false, genrePlaybackIdentityMatches(a,null))
    }

    @Test fun adaptiveColumnsOnTabletHonorScaledCellBudget() {
        assertEquals(2, genreGridColumns(600, 1.5f))
        assertEquals(1, genreGridColumns(520, 1.5f))
        assertEquals(1, genreGridColumns(390, 1.3f))
        assertEquals(1, genreGridColumns(320, 3f))
    }

    @Test fun duplicateTrackIdentityRespectsLibraryOwner() {
        val one = Track("same", "A", genre="Folk", sourceId="one")
        val two = Track("same", "A", genre="Folk", sourceId="two")
        assertEquals(2, groupLibraryGenres(listOf(one,two)).single().tracks.size)
    }

    @Test fun aliasSpellingsUnifyOnlySameStyleWithoutRewritingTracks() {
        val original = listOf(
            song("a", "Synth-Pop"), song("b", "Synthpop"), song("c", "SYNTH POP"))
        val grouped = groupLibraryGenres(original)
        assertEquals(1, grouped.size)
        assertEquals("Synth-Pop", grouped.single().title)
        assertEquals(3, grouped.single().tracks.size)
        assertEquals("Synthpop", original[1].genre)
    }

    @Test fun canonicalTitleDoesNotDependOnTrackOrder() {
        val first = listOf(song("a", "Synthpop"), song("b", "SYNTH POP"))
        val second = first.reversed()
        assertEquals("Synth-Pop", groupLibraryGenres(first).single().title)
        assertEquals(groupLibraryGenres(first).single().title, groupLibraryGenres(second).single().title)
    }

    @Test fun aliasRepeatedInsideTagDoesNotDuplicateSameSong() {
        val genres = groupLibraryGenres(listOf(song("a", "Lo-Fi;Lofi;Lo Fi")
            .copy(genreValues = listOf("Lo-Fi", "Lofi", "Lo Fi"))))
        assertEquals(1, genres.single().tracks.size)
    }

    @Test fun separateSimilarStylesNeverMergeOrInventParentGenres() {
        val genres = groupLibraryGenres(listOf(
            song("a", "Afrobeat"), song("b", "Afrobeats"),
            song("c", "Heavy Metal"), song("d", "Metal"),
            song("e", "Rock/Pop")))
        assertEquals(5, genres.size)
        assertEquals(5, genres.map { it.id }.toSet().size)
    }

    @Test fun actualLibraryCountControlsBothModesIncludingSingleNewWave() {
        val one = groupLibraryGenres(listOf(song("a", "New Wave"), song("b", "new-wave")))
        assertEquals(1, one.size)
        assertEquals(2, one.single().tracks.size)
        assertEquals(GenreEditorialTreatment.COVER, editorialSlot(0, 2).treatment)
        val five = groupLibraryGenres((0 until 5).map { song("$it", "Style $it") })
        assertEquals(5, five.size)
    }

    @Test fun unknownMetadataVariantsDoNotBecomeInventedGenres() {
        val genres = groupLibraryGenres(listOf(song("a", "sin genero"), song("b", "N/A")))
        assertEquals(listOf("Sin clasificar"), genres.map { it.title })
        assertEquals(2, genres.single().tracks.size)
    }

    @Test fun metadataUnitSeparatorIsSupportedWithoutSplittingSlash() {
        val genres = groupLibraryGenres(listOf(song("a", "Jazz\u001fRock/Pop")))
        assertEquals(listOf("Jazz", "Rock/Pop"), genres.map { it.title })
    }

    @Test fun stableDisplayChoiceIndependentOfTrackEnumeration() {
        val first = groupLibraryGenres(listOf(song("1", "jazz"), song("2", "Jazz")))
        val reversed = groupLibraryGenres(listOf(song("2", "Jazz"), song("1", "jazz")))
        assertEquals("Jazz", first.single().title)
        assertEquals(first.map { it.title }, reversed.map { it.title })
        assertEquals("jazz", first.single().tracks.first().genre)
    }

    @Test fun outrageousGenreFieldDoesNotAllocateUnboundedGroups() {
        val long = groupLibraryGenres(listOf(song("1", "X".repeat(9_000))))
        assertEquals(listOf("Sin clasificar"), long.map { it.title })
        val tooMany = groupLibraryGenres(listOf(song("2", (1..33).joinToString(";") { "Style $it" })
            .copy(genreValues = (1..33).map { "Style $it" })))
        assertEquals(listOf("Sin clasificar"), tooMany.map { it.title })
    }

    @Test fun thirtyTwoTagsRemainSupportedWithoutInference() {
        val genres = groupLibraryGenres(listOf(song("1", (1..32).joinToString(";") { "Style $it" })
            .copy(genreValues = (1..32).map { "Style $it" })))
        assertEquals(32, genres.size)
        assertEquals(1, genres.first().tracks.size)
    }

    @Test fun emptyAndRepeatedTokensDoNotExhaustValidGenreQuota() {
        val manySeparators = "Rock" + ";".repeat(200) + "Rock"
        val genres = groupLibraryGenres(listOf(song("1", manySeparators)
            .copy(genreValues = listOf("Rock", "Rock"))))
        assertEquals(listOf("Rock"), genres.map { it.title })
    }
}
