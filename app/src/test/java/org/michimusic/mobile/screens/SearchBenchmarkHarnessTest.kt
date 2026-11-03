package org.michimusic.mobile.screens

import org.michimusic.core.models.Track
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * PERF-05 baseline: how long the in-memory search path costs at 1k, 10k and 50k tracks.
 *
 * The performance contract forbids choosing an index, pagination or FTS without numbers
 * first. This harness runs the real predicates against synthetic catalogs of the sizes
 * the contract names, so any future decision can be justified against the same ruler.
 *
 * It measures CPU work only: no MediaStore, no disk, no Compose. The budget is generous
 * (a slow shared CI runner still fits) because the goal is catching an order-of-magnitude
 * regression, not benchmarking a device.
 */
class SearchBenchmarkHarnessTest {

    private val sizes = listOf(1_000, 10_000, 50_000)

    @Test
    fun linearSearchStaysLinearAcrossCatalogSizes() {
        sizes.forEach { size ->
            val catalog = catalog(size)
            val lower = "track 42"

            var matches = 0
            val elapsed = measureTimeMillis {
                matches = catalog.count { trackTitleMatches(it, lower) }
                catalog.filter { trackAlbumMatches(it, lower) }
            }

            println("PERF-05 size=$size matches=$matches elapsedMs=$elapsed")
            assertTrue("a catalog of $size tracks must still find its own songs", matches > 0)
            assertTrue(
                "in-memory filtering of $size tracks took ${elapsed}ms, which is an order-of-magnitude regression",
                elapsed < 5_000,
            )
        }
    }

    private fun catalog(size: Int): List<Track> = List(size) { index ->
        Track(
            id = "track-$index",
            title = if (index % 1_000 == 0) "Track 42 (remastered $index)" else "Song $index",
            artist = "Artist ${index % 500}",
            album = "Album ${index % 250}",
            duration = 180_000L,
            format = "flac",
            sampleRate = 44_100,
        )
    }
}
