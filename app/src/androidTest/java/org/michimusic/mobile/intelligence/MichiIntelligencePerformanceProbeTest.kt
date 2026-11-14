package org.michimusic.mobile.intelligence

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiLibraryCandidateSelector
import org.michimusic.core.intelligence.MichiTasteFingerprint
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

/**
 * MI-14 opt-in Android instrumented development probe, NOT a release benchmark.
 * Only reads synthesized in-memory metadata. No media scan, audio decode, network,
 * file IO or actual private listening profile. Nothing runs in a shipped app.
 * Run repeatedly on the SAME physical device with fixed thermal/battery state;
 * attach Perfetto/heap and OFF baselines before claiming p95 or energy parity.
 */
@RunWith(AndroidJUnit4::class)
class MichiIntelligencePerformanceProbeTest {
    private fun library(size: Int): List<Track> = List(size) { index ->
        Track(
            id = "synthetic-$index",
            title = "Synthetic $index",
            artist = "Synthetic Artist ${index % 701}",
            album = "Synthetic Album ${index % 1203}",
            genre = when (index % 3) { 0 -> "Rock"; 1 -> "Jazz"; else -> "Pop" },
            sourceId = "",
            source = TrackSource.LOCAL,
            filepath = "/nonexistent/mi_benchmark_$index.flac",
            duration = 180_000L,
        )
    }

    @Test fun profile10kAnd50kBoundedSelectionWithoutAudioOrService() {
        val taste = MichiLibraryCandidateSelector.Signals(
            artists = mapOf(MichiTasteFingerprint.sha256("synthetic artist 7") to 27),
            genres = mapOf(MichiTasteFingerprint.sha256("rock") to 12),
        )
        for (size in listOf(10_000, 50_000)) {
            val songs = library(size)
            repeat(3) { MichiLibraryCandidateSelector.select(songs, taste, 20_000L) }
            val measurements = LongArray(20)
            repeat(measurements.size) { iteration ->
                val started = SystemClock.elapsedRealtimeNanos()
                val result = MichiLibraryCandidateSelector.select(songs, taste, 20_000L)
                measurements[iteration] = SystemClock.elapsedRealtimeNanos() - started
                assertTrue(result.hits.size <= MichiLibraryCandidateSelector.MAX_RESULTS)
                assertTrue(result.hits.isNotEmpty())
            }
            val ordered = measurements.sorted()
            // Logged only numeric timings and synthetic library size, no user data.
            Log.i("MichiIntelligenceProbe", "size=$size p50_us=${ordered[9] / 1000L} " +
                "p95_us=${ordered[18] / 1000L} max_us=${ordered.last() / 1000L}")
        }
    }
}
