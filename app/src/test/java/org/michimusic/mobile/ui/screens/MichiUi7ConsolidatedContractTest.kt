package org.michimusic.mobile.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

/**
 * Consolidated UI7 × MI-26 contract (KILLCRITIC revision 06).
 *
 * It checks the two halves that must agree offline, before any device build:
 *  - the 312 approved identities route Mosaico ↔ Editorial to exactly one asset
 *    each, through the single canonical identity, with their approved filenames;
 *  - the explicit-exception table of the contract (dark wave, electronic ≠
 *    electronica, afrobeat ≠ afrobeats, hip-hop ≠ hip/hop, …) and the deferred
 *    aliases stay exactly as reviewed.
 *
 * This is a JVM contract test: it never claims an Android visual certification.
 */
class MichiUi7ConsolidatedContractTest {
    private val moduleDir: File = File(System.getProperty("user.dir") ?: ".").let { cwd ->
        if (File(cwd, "src/main/java").isDirectory) cwd
        else File(cwd, "app").takeIf { File(it, "src/main/java").isDirectory } ?: cwd
    }
    private val ui7 = File(moduleDir, "src/main/assets/ui7")
    private val mosaicDir = File(ui7, "mosaic")
    private val posterDir = File(ui7, "editorial/posters")

    private fun dataRows(file: File): List<List<String>> = file.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }

    private val artwork by lazy {
        mosaicApprovedArtwork(
            manifest = File(mosaicDir, "manifest.tsv").readText(),
            installed = mosaicDir.listFiles().orEmpty()
                .filter { it.extension == "webp" }
                .map { it.name }
                .toSet(),
        )
    }

    private val posterRows by lazy { dataRows(File(posterDir, "manifest.tsv")) }

    private fun key(label: String): String = genreGroupingKey(label)

    @Test
    fun the312IdentitiesRouteToExactlyOneApprovedAssetPerView() {
        assertEquals("Mosaico rejects or trims the approved pack", 312, artwork.size)
        assertEquals(312, posterRows.size)
        assertEquals(312, posterRows.map { it[0] }.toSet().size)
        // No two catalog identities may collapse into one canonical key.
        assertEquals(312, artwork.keys.size)

        val mosaicSlugs = mutableSetOf<String>()
        artwork.entries.forEach { (canonicalId, asset) ->
            // The manifest may keep its historical spelling ("post rock"); the linking
            // index is the canonical identity used for the exact lookup.
            assertEquals(
                "manifest key must resolve to its index entry: ${asset.genreKey}",
                canonicalId,
                key(asset.genreKey),
            )
            val slug = editorialArtworkSlug(asset.genreKey)
            assertNotNull("missing Editorial identity for ${asset.genreKey}", slug)
            mosaicSlugs += slug!!
            assertTrue(
                "missing approved poster file for $slug",
                File(posterDir, "genre_${slug}_editorial.webp").isFile,
            )
        }
        val posterSlugs = posterRows.map { it[0] }.toSet()
        assertEquals("Mosaico and Editorial are not a 312/312 bijection", posterSlugs, mosaicSlugs)
        // Filenames are the approved ones; nothing is renamed or regenerated.
        assertEquals(
            posterSlugs,
            posterRows.map { row -> row[1].removePrefix("genre_").removeSuffix("_editorial.webp") }
                .toSet(),
        )
    }

    @Test
    fun theContractAcceptanceTableHolds() {
        // P0: one single Dark Wave group, both views.
        assertEquals("darkwave", key("Darkwave"))
        assertEquals("darkwave", key("Dark Wave"))
        assertEquals("darkwave", key("Dark-Wave"))
        assertTrue("darkwave" in artwork)
        assertNotNull(editorialArtworkSlug("Dark Wave"))

        // Coldwave / Synthwave are their own identities, never Dark Wave, never Synth-Pop.
        assertEquals("coldwave", key("Cold Wave"))
        assertEquals("synthwave", key("Synth Wave"))
        assertNotEquals(key("darkwave"), key("coldwave"))
        assertNotEquals(key("synthwave"), key("synth-pop"))
        assertEquals("synth-pop", key("Synth-Pop"))
        assertEquals("synth-pop", key("Synthpop"))

        // Afrobeat and Afrobeats stay two groups with two resources.
        assertNotEquals(key("Afrobeat"), key("Afrobeats"))
        assertTrue(key("Afrobeat") in artwork)
        assertTrue(key("Afrobeats") in artwork)

        // Electronic has no resource of its own; Electronica keeps its own artwork.
        assertEquals("electronic", key("Electronic"))
        assertEquals("electronica", key("Electronica"))
        assertNull(mosaicArtworkForGenre("Electronic", artwork))
        assertNotNull(mosaicArtworkForGenre("Electronica", artwork))
        assertNull(editorialArtworkSlug("Electronic"))
        assertNotNull(editorialArtworkSlug("Electronica"))

        // Jangle Pop is a legitimate label with no approved artwork.
        assertEquals("jangle pop", key("Jangle Pop"))
        assertNull(mosaicArtworkForGenre("Jangle Pop", artwork))
        assertNull(editorialArtworkSlug("Jangle Pop"))

        // Approved word-order exception only for Pop Rock.
        assertEquals("pop rock", key("Pop Rock"))
        assertEquals("pop rock", key("Rock Pop"))
        assertNotNull(mosaicArtworkForGenre("Rock Pop", artwork))

        // Slash is meaningful: never split, never borrow Pop Rock artwork.
        assertEquals("rock/pop", key("Rock/Pop"))
        assertEquals("pop/rock", key("Pop/Rock"))
        assertNotEquals(key("Pop Rock"), key("Rock/Pop"))
        assertNotEquals(key("Pop Rock"), key("Pop/Rock"))
        assertNull(mosaicArtworkForGenre("Rock/Pop", artwork))
        assertNull(mosaicArtworkForGenre("Pop/Rock", artwork))

        // Hip-Hop vs Hip/Hop and R&B vs R B are different identities.
        assertEquals("hip-hop", key("Hip-Hop"))
        assertEquals("hip/hop", key("Hip/Hop"))
        assertNotEquals(key("Hip-Hop"), key("Hip/Hop"))
        assertNotNull(mosaicArtworkForGenre("Hip-Hop", artwork))
        assertNull(mosaicArtworkForGenre("Hip/Hop", artwork))
        assertEquals("r&b", key("R&B"))
        assertEquals("r b", key("R B"))
        assertNotEquals(key("R&B"), key("R B"))
        assertNotNull(mosaicArtworkForGenre("R&B", artwork))
        assertNull(mosaicArtworkForGenre("R B", artwork))

        // Post Rock / Post Metal keep the manifest spelling through the approved aliases.
        assertEquals("post-rock", key("Post Rock"))
        assertEquals("post-rock", key("Post-Rock"))
        assertEquals("post-metal", key("Post Metal"))
        assertEquals("post-metal", key("Post-Metal"))
        assertNotNull(mosaicArtworkForGenre("Post Rock", artwork))
        assertNotNull(mosaicArtworkForGenre("Post Metal", artwork))
        assertNotEquals(key("Post Rock"), key("Post Metal"))

        // No automatic hierarchy or phonetic approximation.
        assertNotEquals(key("Metal"), key("Heavy Metal"))
        assertNotEquals(key("No Wave"), key("New Wave"))
        assertNotEquals(key("J-Rock"), key("J-Pop"))
        assertNotEquals(key("Salsa romántica"), key("Salsa"))

        // Only accents are folded; spelling stays otherwise literal.
        assertEquals("norteno", key("Norteño"))
        assertEquals("norteno", key("Norteno"))
        assertEquals("rai", key("Raï"))
        assertEquals("rai", key("Rai"))
        assertNotNull(mosaicArtworkForGenre("Norteño", artwork))
        assertNotNull(editorialArtworkSlug("Raï"))
    }

    @Test
    fun theRegistryStaysTheReviewed83IdentitiesAndDefersHighEnergy() {
        val identities = MichiGenreDisambiguationPolicy.canonicalIdentities()
        assertEquals(83, identities.size)
        // Every curated identity is stable under re-resolution (idempotent aliases).
        identities.forEach { identity ->
            assertEquals("identity '$identity' is not idempotent", identity, key(identity))
        }
        // `high energy` is deliberately deferred: it must not become Hi-NRG.
        assertNotEquals("hi nrg", key("high energy"))
        assertNotEquals("hi nrg", key("High Energy"))
        assertTrue("hi nrg" in identities)
        // The 29 revisions integrated in b15b42c keep their reviewed identities.
        val reviewed = mapOf(
            "Synth Wave" to "synthwave", "Cold Wave" to "coldwave",
            "Dark Wave" to "darkwave", "New Wave" to "new wave",
            "Neue Deutsche Welle" to "ndw", "Electronic Body Music" to "ebm",
            "Intelligent Dance Music" to "idm", "Soviet Wave" to "sovietwave",
            "Dark Ambient" to "dark ambient", "Minimal Wave" to "minimal wave",
            "No Wave" to "no wave", "Post Punk Revival" to "post punk revival",
            "Russian Post-Punk" to "russian post punk", "Rock Steady" to "rocksteady",
            "G-Funk" to "g funk", "Hi-NRG" to "hi nrg", "Chill Wave" to "chillwave",
            "MPB" to "mpb", "J-Rock" to "j rock", "Art Pop" to "art pop",
            "Modal Jazz" to "modal jazz", "Soul Jazz" to "soul jazz",
            "Ethio Jazz" to "ethio jazz", "Jazz Funk" to "jazz funk",
            "Nu Jazz" to "nu jazz", "Afro-Cuban Jazz" to "afro cuban jazz",
            "Cha-Cha-Chá" to "cha cha cha", "Vaporwave" to "vaporwave",
            "Rocksteady" to "rocksteady",
        )
        reviewed.forEach { (label, expected) ->
            assertEquals("reviewed alias '$label'", expected, key(label))
            assertTrue("'$expected' must remain a curated identity", expected in identities)
        }
    }

    @Test
    fun groupingNeverInventsCategoriesAndNeverLosesATrack() {
        fun track(id: String, genre: String, values: List<String> = emptyList()) = Track(
            id = id, title = id, artist = "A", album = "B",
            filepath = "content://michi/$id", source = TrackSource.LOCAL,
            sourceId = "saf", size = 10L, genre = genre, genreValues = values,
        )
        // Three spellings of the same reviewed identity collapse into ONE card.
        val darkWave = groupLibraryGenres(
            listOf(
                track("1", "Dark Wave"), track("2", "Darkwave"), track("3", "Dark-Wave"),
            ),
        )
        assertEquals(1, darkWave.size)
        assertEquals("darkwave", darkWave.single().id)
        assertEquals(3, darkWave.single().tracks.size)

        // Two legitimate labels without resources keep their own honest groups.
        val fallbacks = groupLibraryGenres(listOf(track("4", "Electronic"), track("5", "Jangle Pop")))
        assertEquals(setOf("electronic", "jangle pop"), fallbacks.map { it.id }.toSet())
        assertEquals(2, fallbacks.size)

        // A verified multi-tag track keeps every identity and every membership.
        val multigenre = groupLibraryGenres(
            listOf(track("6", "", values = listOf("Dark Wave", "Cold Wave", "Synth Pop", "Jangle Pop"))),
        )
        assertEquals(setOf("darkwave", "coldwave", "synth-pop", "jangle pop"),
            multigenre.map { it.id }.toSet())
        assertTrue(multigenre.all { it.tracks.map(Track::id) == listOf("6") })
    }
}
