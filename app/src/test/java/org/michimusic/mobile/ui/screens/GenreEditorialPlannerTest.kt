package org.michimusic.mobile.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editorial planner (doc §3.2–§3.6).
 *
 * The real WebP ratio decides the class: fine/panoramic banners take a full row,
 * compact posters pair with equal height and proportional widths, and a compact
 * that cannot reach the legible minimum becomes a full-width single. The historic
 * `[1,2,2,1]` rhythm is only a visual reference and is never forced.
 */
class GenreEditorialPlannerTest {
    private fun genre(title: String, count: Int = 2) = LibraryGenre(
        id = genreGroupingKey(title),
        title = title,
        tracks = (1..count).map { index ->
            org.michimusic.core.models.Track(id = "${genreGroupingKey(title)}$index", title = title)
        },
        albumCount = 1,
    )

    private fun poster(slug: String, width: Int, height: Int) = EditorialPosterAsset(
        slug = slug,
        filename = "genre_${slug}_editorial.webp",
        widthPx = width,
        heightPx = height,
    )

    private fun postersOf(vararg entries: Pair<String, EditorialPosterAsset>) =
        entries.associate { it.first to it.second }

    private val newWave = poster("new_wave", 1030, 254)      // 4.0551 BANNER_FINO
    private val rock = poster("rock", 1027, 280)            // 3.6679 BANNER
    private val artRock = poster("art_rock", 727, 200)      // 3.6350 BANNER
    private val pop = poster("pop", 514, 268)               // 1.9179 C-W
    private val softRock = poster("soft_rock", 518, 305)    // 1.6984 C-M
    private val synthPop = poster("synth_pop", 513, 327)    // 1.5688 C-A
    private val ndw = poster("ndw", 505, 281)               // 1.7972 C-M
    private val rumba = poster("rumba_cubana", 518, 259)    // 2.0000 C-W

    private fun plan(
        titles: List<Pair<String, EditorialPosterAsset?>>,
        width: Float = 390f,
        columns: Int = 2,
        fontScale: Float = 1f,
        missing: Set<String> = emptySet(),
    ): List<EditorialRow> {
        val genres = titles.map { genre(it.first) }
        val posters = titles.mapNotNull { (title, asset) ->
            asset?.takeIf { it.slug !in missing }?.let { editorialPosterKey(title) to it }
        }.toMap()
        return editorialPlan(genres, posters, width, columns, fontScale)
    }

    /** Mirrors production resolution: "New Wave" -> "new_wave". */
    private fun editorialPosterKey(title: String): String =
        editorialPosterSlug(title) ?: title.lowercase().replace(' ', '_').replace('-', '_')

    @Test fun bannersUseTheFullWidthAndTheirOwnHeight() {
        val rows = plan(listOf("New Wave" to newWave, "Rock" to rock))
        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertTrue(row is EditorialRow.Full)
            val piece = (row as EditorialRow.Full).piece
            assertEquals(350f, piece.widthDp, 0.01f)
            assertEquals(350f / piece.poster!!.ratio, piece.heightDp, 0.05f)
        }
        assertEquals(350f / (1030f / 254f), rows[0].heightDp, 0.05f) // 86.3
        assertEquals(350f / (1027f / 280f), rows[1].heightDp, 0.05f) // 95.4
        assertEquals(EditorialRowKind.BANNER, rows[0].kind)
        assertEquals(EditorialRowKind.BANNER, rows[1].kind)
    }

    @Test fun twoContiguousCompactsFormOnePairWithEqualHeightAndProportionalWidths() {
        val rows = plan(listOf("Pop" to pop, "Soft Rock" to softRock))
        assertEquals(1, rows.size)
        val pair = rows.single() as EditorialRow.Pair
        assertEquals("pair height", 94.0f, pair.heightDp, 0.3f)
        assertEquals("Pop width", 180.3f, pair.leading.widthDp, 0.3f)
        assertEquals("Soft Rock width", 159.7f, pair.trailing.widthDp, 0.3f)
        assertEquals(350f, pair.leading.widthDp + 10f + pair.trailing.widthDp, 0.01f)
        assertEquals(pair.leading.heightDp, pair.trailing.heightDp, 0f)
        assertTrue(pair.leading.widthDp != pair.trailing.widthDp)
    }

    @Test fun synthPopAndPopPairWithinTheMinimumWidth() {
        val rows = plan(listOf("Synth-Pop" to synthPop, "Pop" to pop))
        val pair = rows.single() as EditorialRow.Pair
        assertEquals("pair height", 97.5f, pair.heightDp, 0.3f)
        assertEquals(153.0f, pair.leading.widthDp, 0.4f)
        assertEquals(187.0f, pair.trailing.widthDp, 0.4f)
        assertTrue(minOf(pair.leading.widthDp, pair.trailing.widthDp) >= 150f)
    }

    @Test fun bannersNeverPairWithBannersOrCompacts() {
        val rows = plan(
            listOf(
                "New Wave" to newWave, "Rock" to rock, "Art Rock" to artRock,
                "Rumba Cubana" to rumba,
            ),
        )
        assertTrue("every banner stays on its own full row",
            rows.all { it is EditorialRow.Full })
        assertEquals(4, rows.size)
    }

    @Test fun aCompactWithoutACompatibleNeighbourBecomesAFullWidthSingle() {
        val rows = plan(listOf("Pop" to pop, "New Wave" to newWave))
        assertEquals(2, rows.size)
        val single = rows[0] as EditorialRow.Full
        assertEquals(EditorialRowKind.SINGLE_COMPACT, single.kind)
        assertEquals(350f, single.piece.widthDp, 0.01f)
        assertEquals(350f / pop.ratio, single.piece.heightDp, 0.05f)
    }

    @Test fun theOrderReceivedIsNeverRearrangedToImproveAPair() {
        val titles = listOf(
            "New Wave" to newWave, "Pop" to pop, "Soft Rock" to softRock, "NDW" to ndw,
        )
        val rows = plan(titles)
        val flat = rows.flatMap { row ->
            when (row) {
                is EditorialRow.Full -> listOf(row.piece.genre.title)
                is EditorialRow.Pair -> listOf(row.leading.genre.title, row.trailing.genre.title)
            }
        }
        assertEquals(listOf("New Wave", "Pop", "Soft Rock", "NDW"), flat)
        assertEquals(3, rows.size) // banner, pair(Pop+Soft Rock), single NDW
    }

    @Test fun oneColumnPanelsUseEveryPosterFullWidth() {
        val rows = plan(
            listOf(
                "New Wave" to newWave, "Pop" to pop, "Soft Rock" to softRock,
            ),
            width = 320f,
            columns = 1,
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { it is EditorialRow.Full })
        rows.forEach { row ->
            val piece = (row as EditorialRow.Full).piece
            assertEquals(280f, piece.widthDp, 0.01f)
            assertEquals(280f / piece.poster!!.ratio, piece.heightDp, 0.05f)
        }
    }

    @Test fun aPairThatCannotReachTheMinimumAtLargeTextFallsBackToSingles() {
        val narrowA = poster("afro_house", 513, 317) // 1.6183
        val narrowB = poster("acid_house", 514, 307) // 1.6743
        val atOne = plan(listOf("Afro House" to narrowA, "Acid House" to narrowB))
        assertEquals("at 1.0 the pair fits", 1, atOne.size)
        assertTrue(atOne.single() is EditorialRow.Pair)

        val atTwo = plan(listOf("Afro House" to narrowA, "Acid House" to narrowB), fontScale = 2f)
        assertEquals("at 2.0 the 182dp minimum cannot be met", 2, atTwo.size)
        assertTrue(atTwo.all { it is EditorialRow.Full })
        assertEquals(182f, editorialPairMinWidthDp(2f), 0.01f)
        assertEquals(150f, editorialPairMinWidthDp(1f), 0.01f)
        assertEquals(166f, editorialPairMinWidthDp(1.5f), 0.01f)
    }

    @Test fun aGenreWithoutAPosterKeepsItsPositionAndDoesNotStealAPartner() {
        val rows = plan(
            listOf(
                "Pop" to pop,
                "Ghost" to null,
                "Soft Rock" to softRock,
                "NDW" to ndw,
            ),
        )
        assertEquals("the fallback keeps its own row", 3, rows.size)
        val first = rows[0] as EditorialRow.Full
        assertEquals("Pop", first.piece.genre.title)
        assertEquals(EditorialRowKind.FALLBACK, rows[1].kind)
        assertEquals("Ghost", (rows[1] as EditorialRow.Full).piece.genre.title)
        // The missing asset does not consume a partner: the next two real compacts
        // still pair, in the received order.
        val pair = rows[2] as EditorialRow.Pair
        assertEquals("Soft Rock", pair.leading.genre.title)
        assertEquals("NDW", pair.trailing.genre.title)
        assertTrue(rows.all { row ->
            row !is EditorialRow.Pair ||
                (row.leading.poster != null && row.trailing.poster != null)
        })
    }

    @Test fun rowsAreNeverOrphanedAndKeysAreStable() {
        val titles = listOf(
            "New Wave" to newWave, "Pop" to pop, "Soft Rock" to softRock,
            "Rock" to rock, "NDW" to ndw, "Synth-Pop" to synthPop,
        )
        val rows = plan(titles)
        val ids = rows.flatMap { row ->
            when (row) {
                is EditorialRow.Full -> listOf(row.piece.genre.id)
                is EditorialRow.Pair -> listOf(row.leading.genre.id, row.trailing.genre.id)
            }
        }
        assertEquals("every genre appears exactly once", titles.size, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(rows.map { it.key }, plan(titles).map { it.key })
        assertEquals(rows.map { it.key }.size, rows.map { it.key }.toSet().size)
        val pairs = rows.filterIsInstance<EditorialRow.Pair>()
        pairs.forEach { pair ->
            assertEquals(pair.heightDp, pair.leading.heightDp, 0f)
            assertEquals(pair.heightDp, pair.trailing.heightDp, 0f)
        }
    }

    @Test fun theReal312InventoryClassifiesOnceWithTheContractTotals() {
        val rows = realPosterRows()
        assertEquals(312, rows.size)
        val classes = rows.map { editorialPosterClass(it[3].toInt() / it[4].toFloat()) }
        val counts = classes.groupingBy { it }.eachCount()
        assertEquals(43, counts[EditorialPosterClass.COMPACTO_ALTO] ?: 0)
        assertEquals(169, counts[EditorialPosterClass.COMPACTO_MEDIO] ?: 0)
        assertEquals(22, counts[EditorialPosterClass.COMPACTO_ANCHO] ?: 0)
        assertEquals(36, counts[EditorialPosterClass.BANNER_PANORAMICO] ?: 0)
        assertEquals(42, counts[EditorialPosterClass.BANNER_FINO] ?: 0)
        assertEquals("no portrait and no intermediate resource exists", 0,
            counts[EditorialPosterClass.RETRATO_FUTURO] ?: 0)
        assertEquals(0, counts[EditorialPosterClass.INTERMEDIO_FUTURO] ?: 0)
        assertEquals("each entry is classified exactly once", 312, classes.size)
        assertEquals("78 banners + 234 compacts", 78,
            (counts[EditorialPosterClass.BANNER_PANORAMICO] ?: 0) +
                (counts[EditorialPosterClass.BANNER_FINO] ?: 0))
    }

    @Test fun theRealInventoryPlansWithoutLosingOrDuplicatingAGenre() {
        val rows = realPosterRows()
        val titles = rows.map { it[0] to (it[1] to (it[3].toInt() to it[4].toInt())) }
        val genres = titles.map { (slug, _) -> LibraryGenre(slug, slug, emptyList(), 0) }
        val posters = titles.associate { (slug, data) ->
            val (filename, dims) = data
            slug to EditorialPosterAsset(slug, filename, dims.first, dims.second)
        }
        listOf(320 to 1, 360 to 1, 390 to 2, 411 to 2, 480 to 2).forEach { (width, columns) ->
            val plan = editorialPlan(genres, posters, width.toFloat(), columns, 1f)
            val ids = plan.flatMap { row ->
                when (row) {
                    is EditorialRow.Full -> listOf(row.piece.genre.id)
                    is EditorialRow.Pair -> listOf(row.leading.genre.id, row.trailing.genre.id)
                }
            }
            assertEquals("[$width] every genre appears once", 312, ids.size)
            assertEquals("[$width] no duplicate", 312, ids.toSet().size)
            assertFalse("[$width] no asset-less fallback", plan.any {
                it.kind == EditorialRowKind.FALLBACK
            })
            plan.forEach { row ->
                when (row) {
                    is EditorialRow.Full -> {
                        assertEquals("[$width] full rows use U",
                            editorialUsableWidthDp(width.toFloat()), row.piece.widthDp, 0.01f)
                        assertEquals("[$width] full row height follows the real ratio",
                            row.piece.widthDp / row.piece.poster!!.ratio, row.heightDp, 0.05f)
                    }
                    is EditorialRow.Pair -> {
                        val sum = row.leading.widthDp + 10f + row.trailing.widthDp
                        assertEquals("[$width] pair fills U",
                            editorialUsableWidthDp(width.toFloat()), sum, 0.05f)
                        assertEquals(row.leading.heightDp, row.trailing.heightDp, 0f)
                    }
                }
            }
        }
    }

    private fun realPosterRows(): List<List<String>> {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val module = if (File(cwd, "src/main/java").isDirectory) cwd
        else File(cwd, "app").takeIf { File(it, "src/main/java").isDirectory } ?: cwd
        val manifest = File(module, "src/main/assets/ui7/editorial/posters/manifest.tsv")
        val installed = manifest.parentFile?.listFiles().orEmpty().associateBy { it.name }
        return manifest.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it[1] in installed }
    }
}
