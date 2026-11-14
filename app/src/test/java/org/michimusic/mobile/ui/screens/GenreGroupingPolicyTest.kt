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

    @Test fun geometryReservesRealCardWidthAndMeasuredTextSpace() {
        // Pure arithmetic: 20dp gutters + 10dp gap are fixed, only the measured
        // minimum tile width changes the column count.
        assertEquals(1, genreGridColumns(320, 148))
        assertEquals(2, genreGridColumns(360, 148))
        assertEquals(2, genreGridColumns(390, 148))
        assertEquals(2, genreGridColumns(411, 148))
        assertEquals(2, genreGridColumns(480, 148))
        // A wider measured label must downgrade to one column instead of
        // squeezing two unreadable cards.
        assertEquals(1, genreGridColumns(360, 160))
        assertEquals(1, genreGridColumns(390, 175))
        assertEquals(2, genreGridColumns(480, 200))
        assertEquals(1, genreGridColumns(320, 200))
        // Fixed rhythm remains 192/246/200 for Compose-drawn cells.
        assertEquals(216, genreEditorialHeightDp(GenreEditorialTreatment.COVER, 2, 2f))
        assertEquals(270, genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, 2, 2f))
    }

    @Test fun productionColumnContractIsOneColumnUpTo360() {
        // Latest approved contract: 320 -> 1, 360 -> 1, 390/411/480 -> 2.
        val floor = GENRE_COMPACT_TILE_MIN_WIDTH_DP
        assertEquals(156, floor)
        assertEquals(1, genreGridColumns(320, floor))
        assertEquals(1, genreGridColumns(360, floor))
        assertEquals(2, genreGridColumns(390, floor))
        assertEquals(2, genreGridColumns(411, floor))
        assertEquals(2, genreGridColumns(480, floor))
        // The fixed gutters are 20dp and the gap is 10dp, never derived from width.
        assertEquals(20, GENRE_GRID_SIDE_PADDING_DP)
        assertEquals(10, GENRE_GRID_GAP_DP)
        assertEquals(4, GENRE_GRID_TOP_PADDING_DP)
        assertEquals(28, GENRE_GRID_BOTTOM_PADDING_DP)
    }

    @Test fun heroAndSecondaryHeightsComeFromTheTreatmentNotTheWidth() {
        // 218dp focal card and 166dp compact card at 1.0x, for every panel width.
        listOf(280f, 320f, 350f, 371f, 440f).forEach { width ->
            assertEquals("hero must not follow $width", 218, genreMosaicHeroHeightDp(1f))
            assertEquals("secondary must not follow $width", 166, genreMosaicSecondaryHeightDp(1f))
        }
        // Larger text grows the cards by a bounded amount; no card shrinks below
        // the 48dp touch floor and no font is reduced.
        assertEquals(230, genreMosaicHeroHeightDp(1.3f))
        assertEquals(258, genreMosaicHeroHeightDp(2f))
        assertEquals(172, genreMosaicSecondaryHeightDp(1.3f))
        assertEquals(186, genreMosaicSecondaryHeightDp(2f))
        assertEquals(298, genreMosaicHeroHeightDp(3f))
        assertEquals(206, genreMosaicSecondaryHeightDp(3f))
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

    @Test fun adaptiveColumnsOnTabletHonorMeasuredCellBudget() {
        // A foldable/tablet pane can still pair two cards with a large measured
        // label, and stops pairing when the measured label no longer fits.
        assertEquals(2, genreGridColumns(600, 200))
        assertEquals(1, genreGridColumns(520, 260))
        assertEquals(1, genreGridColumns(390, 180))
        assertEquals(1, genreGridColumns(320, 148))
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
