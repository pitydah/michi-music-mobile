package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** UI7 image-free Compose navigation/semantics gate. Android execution is required. */
@RunWith(AndroidJUnit4::class)
class GenreLibraryPanelComposeTest {
    @get:Rule val rule = createComposeRule()

    /** Opt-in visual evidence: enabled with `-e michiGenreEvidence true`; off by default. */
    private val evidenceDir: File? =
        if (InstrumentationRegistry.getArguments().getString("michiGenreEvidence") == "true") {
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "ui7-p0-genre-layout").apply { mkdirs() }
        } else null

    private fun captureEvidence(name: String) {
        val directory = evidenceDir ?: return
        val bitmap = rule.onNodeWithTag("genres_overview").captureToImage().asAndroidBitmap()
        FileOutputStream(File(directory, "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun tracks(): List<Track> = listOf(
        Track(id = "j1", title = "Jazz One", artist = "Artist A", album = "Album A",
            sourceId = "local", filepath = "content://music/j1", genre = "Jazz"),
        Track(id = "r1", title = "Rock One", artist = "Artist B", album = "Album B",
            sourceId = "local", filepath = "content://music/r1", genre = "Rock"),
        Track(id = "f1", title = "Folk One", artist = "Artist C", album = "Album C",
            sourceId = "local", filepath = "content://music/f1", genre = "Folk"),
    )

    @Test fun mosaicAndEditorialNavigateTheSameRealGenresWithoutImages() {
        val genres = groupLibraryGenres(tracks())
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                GenreLibraryPanel(genres, genres.flatMap { it.tracks }.map { it.id }.toSet(),
                    currentTrack = null, isPlaying = false, onPlay = { _, _ -> })
            }
        }
        rule.onNodeWithTag("genres_featured").assertExists()
        rule.onNodeWithTag("genre_tile_folk").assertContentDescriptionEquals("Abrir género Folk, 1 pista")
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.onNodeWithTag("genre_view_editorial").performClick()
        rule.onNodeWithTag("genres_editorial").assertExists()
        rule.onNodeWithTag("genre_editorial_folk").assertExists().performClick()
        rule.onNodeWithTag("genre_detail").assertExists()
        rule.onNodeWithTag("genre_detail_title").assertExists()
        rule.onNodeWithTag("genre_track_f1").assertExists()
    }

    @Test fun genreSearchOnlyFiltersCurrentCollection() {
        val genres = groupLibraryGenres(
            tracks() + listOf("Blues", "Ambient", "Clásica", "Soul", "Metal", "Pop", "Reggae")
                .mapIndexed { index, genre -> Track(id = "x$index", title = "X$index", genre = genre) },
        )
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(genres, genres.flatMap { it.tracks }.map { it.id }.toSet(),
                    currentTrack = null, isPlaying = false, onPlay = { _, _ -> })
            }
        }
        rule.onNodeWithTag("genres_search_input").performTextInput("JAZZ")
        rule.onNodeWithTag("genre_tile_jazz").assertExists()
        rule.onNodeWithTag("genre_tile_rock").assertDoesNotExist()
        rule.onNodeWithTag("genres_search_clear").performClick()
        rule.onNodeWithTag("genres_featured").assertExists()
    }

    @Test fun detailPlaysOnlyAuthorizedTracksWithRealIndex() {
        val songs = listOf(
            Track(id = "a", title = "A", genre = "Jazz", sourceId = "one", filepath = "a.flac"),
            Track(id = "b", title = "B", genre = "Jazz", sourceId = "one", filepath = "b.flac"),
        )
        var lastIds: List<String> = emptyList()
        var lastIndex = -1
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(songs), setOf("b"), currentTrack = null,
                    isPlaying = false, onPlay = { queue, index ->
                        lastIds = queue.map { it.id }; lastIndex = index
                    })
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_a").assertIsNotEnabled()
        rule.onNodeWithTag("genre_track_b").performClick()
        rule.runOnIdle { assertEquals(listOf("b"), lastIds); assertEquals(0, lastIndex) }
    }

    @Test fun duplicateOwnerIdsAreNotAccidentallyAuthorized() {
        val songs = listOf(
            Track(id = "dup", title = "One", genre = "Jazz", sourceId = "one", filepath = "one.flac"),
            Track(id = "dup", title = "Two", genre = "Jazz", sourceId = "two", filepath = "two.flac"),
            Track(id = "unique", title = "Unique", genre = "Jazz", sourceId = "one", filepath = "ok.flac"),
        )
        var played: List<String> = emptyList()
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(songs), setOf("dup", "unique"), null,
                    isPlaying = false, onPlay = { queue, _ -> played = queue.map { it.id } })
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_unique").performClick()
        rule.runOnIdle { assertEquals(listOf("unique"), played) }
    }

    @Test fun responsiveModeKeepsUsableGenreTileBoundsWithoutAssets() {
        data class Scenario(val width: Int, val fontScale: Float)
        var scenario by mutableStateOf(Scenario(320, 1f))
        val genres = groupLibraryGenres(tracks())
        rule.setContent {
            val config = Configuration().apply {
                screenWidthDp = scenario.width
                fontScale = scenario.fontScale
            }
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(density = 2f, fontScale = scenario.fontScale),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true,
                    highContrast = true)) {
                    Box(Modifier.width(scenario.width.dp)) {
                        GenreLibraryPanel(genres, setOf("j1", "r1", "f1"), null,
                            isPlaying = false, onPlay = { _, _ -> })
                    }
                }
            }
        }
        for (s in listOf(Scenario(320, 1f), Scenario(360, 1f),
            Scenario(390, 1f), Scenario(411, 1.3f), Scenario(480, 2f))) {
            rule.runOnIdle { scenario = s }
            rule.waitForIdle()
            rule.onNodeWithTag("genres_featured").assertExists()
            val hero = rule.onNodeWithTag("genre_tile_folk").fetchSemanticsNode().boundsInRoot
            assertTrue("Genre tile tappable at $s", hero.height >= 48 * 2f)
        }
    }

    /**
     * P0 regression: a real 11-genre library must keep the search field compact and
     * leave the first card actually visible. `assertExists` is deliberately not used
     * as a visibility substitute; bounds prove the card is below the search field and
     * has positive visible area.
     */
    @Test fun searchFieldStaysCompactAndLeavesFirstCardVisibleWithElevenGenres() {
        val names = listOf("Jazz", "Rock", "Folk", "Blues", "Ambient", "Clásica",
            "Soul", "Metal", "Pop", "Reggae", "Tango")
        val songs = names.mapIndexed { index, genre ->
            Track(id = "g$index", title = "Track $index", artist = "Artist $index",
                album = "Album $index", sourceId = "local",
                filepath = "content://music/g$index", genre = genre)
        }
        val genres = groupLibraryGenres(songs)
        assertEquals(11, genres.size)
        val firstGenre = genresByTrackCount(genres).first().id

        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                GenreLibraryPanel(genres, songs.map { it.id }.toSet(), currentTrack = null,
                    isPlaying = false, onPlay = { _, _ -> })
            }
        }
        rule.waitForIdle()

        val density = rule.density
        val maxSearchHeightPx = with(density) { 64.dp.toPx() }
        val minSearchHeightPx = with(density) { 48.dp.toPx() }
        val tolerancePx = with(density) { 0.5.dp.toPx() }
        fun searchBounds() = rule.onNodeWithTag("genres_search_field")
            .fetchSemanticsNode().boundsInRoot
        fun assertCompactSearch(label: String) {
            val search = searchBounds()
            assertTrue("$label: search field must stay compact, was ${search.height}px",
                search.height <= maxSearchHeightPx)
            assertTrue("$label: search field must keep its 48dp target, was ${search.height}px",
                search.height >= minSearchHeightPx)
        }

        // Mosaico: the field and the first card are both genuinely on screen.
        rule.onNodeWithTag("genres_search_field").assertIsDisplayed()
        assertCompactSearch("initial Mosaico")
        val evidence = mutableListOf<String>()
        fun record(line: String) {
            println(line)
            evidence += line
        }
        val mosaicSearchBottom = searchBounds().bottom
        record("mosaic_search=${searchBounds()}")
        val firstMosaic = rule.onNodeWithTag("genre_tile_$firstGenre")
        firstMosaic.assertIsDisplayed()
        val mosaicCard = firstMosaic.fetchSemanticsNode().boundsInRoot
        record("mosaic_first_card=$mosaicCard")
        assertTrue("First Mosaico card must have visible area, was $mosaicCard",
            mosaicCard.height > 0f)
        assertTrue("First Mosaico card must sit below the search field: " +
            "searchBottom=$mosaicSearchBottom card=$mosaicCard",
            mosaicCard.top >= mosaicSearchBottom - tolerancePx)
        assertTrue("First Mosaico card must not overlap the search field",
            !searchBounds().overlaps(mosaicCard))

        // Typing and clearing keep the same compact field and focus.
        rule.onNodeWithTag("genres_search_input").performClick()
        rule.onNodeWithTag("genres_search_input").assertIsFocused()
        rule.onNodeWithTag("genres_search_input").performTextInput("JAZZ")
        rule.waitForIdle()
        assertCompactSearch("filtered Mosaico")
        rule.onNodeWithTag("genres_search_clear").performClick()
        rule.waitForIdle()
        assertCompactSearch("cleared Mosaico")
        captureEvidence("mosaico-11genres")

        // Mosaico -> Editorial -> Mosaico must not grow the field either.
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.onNodeWithTag("genre_view_editorial").performClick()
        rule.waitForIdle()
        assertCompactSearch("Editorial")
        val editorialSearchBottom = searchBounds().bottom
        record("editorial_search=${searchBounds()}")
        val firstEditorial = rule.onNodeWithTag("genre_editorial_$firstGenre")
        firstEditorial.assertIsDisplayed()
        val editorialCard = firstEditorial.fetchSemanticsNode().boundsInRoot
        record("editorial_first_card=$editorialCard")
        assertTrue("First Editorial card must have visible area, was $editorialCard",
            editorialCard.height > 0f)
        assertTrue("First Editorial card must sit below the search field: " +
            "searchBottom=$editorialSearchBottom card=$editorialCard",
            editorialCard.top >= editorialSearchBottom - tolerancePx)
        assertTrue("First Editorial card must not overlap the search field",
            !searchBounds().overlaps(editorialCard))
        captureEvidence("editorial-11genres")

        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.onNodeWithTag("genre_view_featured").performClick()
        rule.waitForIdle()
        assertCompactSearch("returned Mosaico")
        rule.onNodeWithTag("genre_tile_$firstGenre").assertIsDisplayed()
        captureEvidence("mosaico-11genres-returned")
        evidenceDir?.let { File(it, "bounds.txt").writeText(evidence.joinToString("\n")) }
    }

    /**
     * P0 span regression: completed posters are horizontal. Clásica (1032x255),
     * Metal, Rock and Reggae are approved ~3.6-4.1:1 panels, and the positional
     * COVER/LEFT/RIGHT rhythm parks some of them in one-column PORTRAIT slots.
     * There they render as ~44 dp strips and leave the rest of the row empty, so
     * the span must come from the approved poster ratio (>= 2.4:1 = full width),
     * never from the index rhythm. Compact posters keep the canonical treatment.
     * ContentScale.Fit is preserved: no crop, no letterbox bands, no asset change.
     */
    @Test fun editorialPosterSpansFollowApprovedRatioNotIndexRhythm() {
        val names = listOf("Jazz", "Rock", "Folk", "Blues", "Ambient", "Clásica",
            "Soul", "Metal", "Pop", "Reggae", "Tango")
        val songs = names.mapIndexed { index, genre ->
            Track(id = "g$index", title = "Track $index", artist = "Artist $index",
                album = "Album $index", sourceId = "local",
                filepath = "content://music/g$index", genre = genre)
        }
        val genres = groupLibraryGenres(songs)
        assertEquals(11, genres.size)
        val ordered = genresByTrackCount(genres)
        // All genres have one track, so the panel order is alphabetical. Pin it:
        // Clásica lands on index 2 (PORTRAIT_RIGHT) and used to be a one-column strip.
        assertEquals(listOf("Ambient", "Blues", "Clásica", "Folk", "Jazz", "Metal",
            "Pop", "Reggae", "Rock", "Soul", "Tango"), ordered.map { it.title })
        val expectedOneColumn = listOf("Blues", "Jazz", "Tango")
        val expectedFullWidth = listOf("Ambient", "Clásica", "Folk", "Metal",
            "Pop", "Reggae", "Rock", "Soul")

        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val installed = assets.list(editorialPosterFolder).orEmpty().toSet()
        val manifest = assets.open("$editorialPosterFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val posters = editorialApprovedPosters(manifest, installed)
        val posterByTitle = ordered.associate { genre ->
            genre.title to (editorialPosterForTitle(genre.title, posters)
                ?: error("Missing approved poster for ${genre.title}"))
        }
        val clasica = ordered.first { it.title == "Clásica" }
        val clasicaPoster = posterByTitle.getValue(clasica.title)
        assertTrue("Clásica must stay an ultra-wide approved poster, was ${clasicaPoster.ratio}",
            clasicaPoster.ratio >= 2.4f)
        // Documentary: the rhythm alone would force one column on this index.
        assertEquals(1, editorialSlot(ordered.indexOf(clasica), 2).span)

        val loaded = ConcurrentHashMap.newKeySet<String>()
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                GenreLibraryPanel(genres, songs.map { it.id }.toSet(), currentTrack = null,
                    isPlaying = false, onPlay = { _, _ -> },
                    onArtworkLoad = { view, genreId, ok ->
                        if (view == GenreViewMode.EDITORIAL && ok) loaded += genreId
                    })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("genres_overview").assertIsDisplayed()
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.onNodeWithTag("genre_view_editorial").performClick()
        rule.waitForIdle()

        val density = rule.density
        val oneDp = with(density) { 1.dp.toPx() }
        val gap = with(density) { 10.dp.toPx() }
        val minPosterHeight = with(density) { 48.dp.toPx() }
        val maxSearchHeight = with(density) { 64.dp.toPx() }
        val evidence = mutableListOf<String>()
        fun record(line: String) {
            println(line)
            evidence += line
        }
        fun searchBounds() = rule.onNodeWithTag("genres_search_field")
            .fetchSemanticsNode().boundsInRoot
        fun assertCompactSearch(label: String) {
            val search = searchBounds()
            assertTrue("$label: search field must stay compact, was ${search.height}px",
                search.height <= maxSearchHeight)
        }
        fun tileBounds(id: String) = rule.onNodeWithTag("genre_editorial_$id")
            .fetchSemanticsNode().boundsInRoot
        fun posterBounds(id: String) = rule.onNodeWithTag("genre_editorial_poster_$id",
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun scrollTo(id: String) {
            rule.onNodeWithTag("genres_editorial")
                .performScrollToNode(hasTestTag("genre_editorial_$id"))
            rule.waitForIdle()
        }

        assertCompactSearch("Editorial")
        val editorialSearch = searchBounds()
        record("editorial_search=$editorialSearch")
        scrollTo(ordered.first().id)
        val firstCard = tileBounds(ordered.first().id)
        record("editorial_first_card=$firstCard")
        assertTrue("First Editorial card must have visible area, was $firstCard",
            firstCard.height > 0f)
        assertTrue("First Editorial card must sit below the search field: " +
            "searchBottom=${editorialSearch.bottom} card=$firstCard",
            firstCard.top >= editorialSearch.bottom - oneDp)
        rule.onNodeWithTag("genre_editorial_${ordered.first().id}").assertIsDisplayed()

        data class PosterRow(
            val title: String,
            val poster: EditorialPosterAsset,
            val tile: Rect,
            val image: Rect,
        )
        val rows = ordered.map { genre ->
            scrollTo(genre.id)
            // Prove the approved WebP actually decoded while its tile is composed;
            // geometry alone would not catch a poster that failed to load.
            rule.waitUntil(10_000) { loaded.contains(genre.id) }
            PosterRow(genre.title, posterByTitle.getValue(genre.title),
                tileBounds(genre.id), posterBounds(genre.id))
        }
        rule.waitForIdle()
        val fullWidth = rows.first { it.title == "Ambient" }.tile.width
        val oneColumn = rows.first { it.title == "Blues" }.tile.width
        rows.forEach { row ->
            val emptyVertical = (row.image.height - row.image.width / row.poster.ratio)
                .coerceAtLeast(0f) / density.density
            val emptyHorizontal = (row.image.width - row.image.height * row.poster.ratio)
                .coerceAtLeast(0f) / density.density
            record("${row.title} span=${if (row.title in expectedFullWidth) 2 else 1} " +
                "tile=${row.tile} poster=${row.image} ratio=${row.poster.ratio} " +
                "emptyV=${emptyVertical}dp emptyH=${emptyHorizontal}dp")
        }
        record("full_width=${fullWidth / density.density}dp " +
            "one_column=${oneColumn / density.density}dp")
        // Persist geometry before asserting: a failing pre-fix run must still leave
        // the measured strip bounds as evidence.
        evidenceDir?.let { File(it, "editorial-bounds.txt").writeText(evidence.joinToString("\n")) }
        scrollTo(ordered.first().id)
        captureEvidence("editorial-11genres-top")
        scrollTo(clasica.id)
        captureEvidence("editorial-clasica")
        scrollTo(ordered.first().id)

        assertTrue("Two-column grid required for this check: full=$fullWidth", fullWidth > 0f)
        assertTrue("One-column cells must be narrower than full-width cells: " +
            "one=$oneColumn full=$fullWidth", oneColumn < fullWidth * 0.6f)
        assertTrue("One column must keep its canonical width: one=$oneColumn",
            abs(oneColumn - (fullWidth - gap) / 2) <= 2 * oneDp)

        rows.forEach { row ->
            val expectedWidth = if (row.title in expectedFullWidth) fullWidth else oneColumn
            val tile = row.tile
            val image = row.image
            val label = if (row.title in expectedOneColumn) "one column" else "full width"
            assertTrue("${row.title} must keep its $label span: tile=$tile " +
                "expected=${expectedWidth / density.density}dp",
                abs(tile.width - expectedWidth) <= 2 * oneDp)
            assertTrue("${row.title} poster must fill the cell width: tile=$tile image=$image",
                abs(image.width - tile.width) <= oneDp)
            val emptyVertical = (image.height - image.width / row.poster.ratio).coerceAtLeast(0f)
            val emptyHorizontal = (image.width - image.height * row.poster.ratio)
                .coerceAtLeast(0f)
            assertTrue("${row.title} has empty bands: vertical=" +
                "${emptyVertical / density.density}dp horizontal=" +
                "${emptyHorizontal / density.density}dp inside $image",
                emptyVertical <= oneDp && emptyHorizontal <= oneDp)
            assertTrue("${row.title} poster aspect ${image.width / image.height} " +
                "!= ${row.poster.ratio}",
                abs(image.width / image.height - row.poster.ratio) <= 0.02f)
            assertTrue("${row.title} must keep a 48dp target: $tile",
                tile.height >= minPosterHeight)
        }

        val clasicaRow = rows.first { it.title == "Clásica" }
        assertTrue("Clásica must occupy both columns: tile=${clasicaRow.tile} " +
            "full=$fullWidth one=$oneColumn",
            abs(clasicaRow.tile.width - fullWidth) <= 2 * oneDp)
        assertTrue("Clásica poster must be at least 48dp tall without bands: " +
            "image=${clasicaRow.image}", clasicaRow.image.height >= minPosterHeight)
        expectedOneColumn.forEach { title ->
            val row = rows.first { it.title == title }
            assertTrue("$title is a compact poster and must stay one column: tile=${row.tile}",
                abs(row.tile.width - oneColumn) <= 2 * oneDp)
        }

        // Mosaico -> Editorial -> Mosaico keeps the compact search and first card.
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.onNodeWithTag("genre_view_featured").performClick()
        rule.waitForIdle()
        assertCompactSearch("returned Mosaico")
        val mosaicoSearch = searchBounds()
        val firstMosaico = rule.onNodeWithTag("genre_tile_${ordered.first().id}")
        firstMosaico.assertIsDisplayed()
        val mosaicoCard = firstMosaico.fetchSemanticsNode().boundsInRoot
        record("mosaico_search=$mosaicoSearch mosaico_first_card=$mosaicoCard")
        assertTrue("First Mosaico card must sit below the search field: " +
            "searchBottom=${mosaicoSearch.bottom} card=$mosaicoCard",
            mosaicoCard.top >= mosaicoSearch.bottom - oneDp)
        assertTrue("First Mosaico card must have visible area, was $mosaicoCard",
            mosaicoCard.height > 0f)
        evidenceDir?.let { File(it, "editorial-bounds.txt")
            .writeText(evidence.joinToString("\n")) }
    }

    @Test fun blockedOwnerIdNeverReachesPlaybackEvenWhenGenreHasSingleTrack() {
        val only = Track(id = "shared", title = "Jazz", genre = "Jazz",
            sourceId = "peer-a", filepath = "one.flac")
        var invoked = false
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(listOf(only)), setOf("shared"), null,
                    isPlaying = false, onPlay = { _, _ -> invoked = true },
                    globallyBlockedTrackIds = setOf("shared"))
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_shared").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(false, invoked) }
    }
}
