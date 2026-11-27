package org.michimusic.mobile.perf

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.ArtistEnrichmentSummaryContent
import org.michimusic.mobile.artist.ArtistEnrichmentUiState
import org.michimusic.mobile.artist.ArtistPhotoAvailability
import org.michimusic.mobile.artist.ArtistPhotoSlot
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.ui.theme.MichiTheme

/**
 * K48-R12-09 surface probe.
 *
 * This is an opt-in instrumented measurement, not a release benchmark. It records what a
 * real device actually spent to put a K48 surface on screen: the wall time from the first
 * composition request to the moment the surface reports itself ready, plus the process
 * memory the pass added.
 *
 * Deliberately NOT measured here, because this probe cannot observe them honestly:
 *  - HTTP requests per action: there is no interceptor wired into the production client and
 *    no usage-access grant on the device, so any number would be invented. The consent gate
 *    is verified instead at the boundary it owns: no provider callback runs before the user
 *    confirms (ArtistPhotoConsentGateTest).
 *  - jank: `dumpsys gfxinfo <pkg> framestats` is the authority for that and is read from the
 *    host around this run, not recomputed inside the process.
 *
 * Samples are wall-clock around [androidx.compose.ui.test.junit4.ComposeContentTestRule],
 * so they include real composition and layout, and they are recorded only when the surface
 * actually appeared.
 */
@RunWith(AndroidJUnit4::class)
class K48R12SurfacePerformanceProbeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val iterations: Int = InstrumentationRegistry.getArguments()
        .getString("michiProbeIterations")?.toIntOrNull() ?: 20

    /**
     * Artifacts are named per run so a rerun cannot silently append to numbers produced on
     * another device or another build. Pass `-e michiProbeTag <tag>` to label a session.
     */
    private val runTag: String = InstrumentationRegistry.getArguments()
        .getString("michiProbeTag") ?: "local"

    @Test
    fun artistSummaryOpeningIsMeasuredOnDevice() {
        // The summary suppresses a duplicate primary button when ViewPhotos opens
        // the same picker as the slot cards, so the stable surface-ready signal is
        // the first artwork slot, not a button that intentionally may not exist.
        measureSurface("artist_enrichment_summary", "artist_artwork_slot_ARTIST_THUMB") {
            ArtistEnrichmentSummaryContent(
                uiState = summaryState(),
                onChoosePhoto = {},
                onPrimaryAction = {},
            )
        }
    }

    @Test
    fun artistSlotFirstImageIsMeasuredOnDevice() {
        // The saved-photo slot draws from a local file; the measure stops once the slot
        // reports itself, which is the moment the user can act on it.
        measureSurface("artist_enrichment_slot_ready", "artist_artwork_slot_ARTIST_HERO") {
            ArtistEnrichmentSummaryContent(
                uiState = summaryState(),
                onChoosePhoto = {},
                onPrimaryAction = {},
            )
        }
    }

    private fun measureSurface(metric: String, readyTag: String, content: @Composable () -> Unit) {
        val samples = ArrayList<Long>(iterations)
        var index = 0
        // A single composition host; each iteration rebuilds the subtree from scratch so the
        // sample is an opening, not a recomposition of something already measured.
        compose.setContent {
            MichiTheme {
                Box(Modifier.width(390.dp).fillMaxHeight().background(Color(0xFF090C12))) {
                    SurfaceUnderMeasure(index, content)
                }
            }
        }
        repeat(iterations) { pass ->
            compose.waitForIdle()
            val memoryBefore = Debug.getNativeHeapAllocatedSize()
            val startedAt = SystemClock.elapsedRealtimeNanos()
            compose.runOnIdle { index = pass + 1 }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag(readyTag).fetchSemanticsNodes().isNotEmpty()
            }
            val elapsedUs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000L
            val memoryAfter = Debug.getNativeHeapAllocatedSize()
            // PSS is the number a reviewer can compare against `dumpsys meminfo`; the native
            // heap alone understates what a Compose surface costs once bitmaps are resident.
            val pssKb = Debug.getPss()
            if (pass >= 2) samples += elapsedUs // discard the first two passes as warm-up
            // One row per sample keeps the memory delta reproducible from the artifact.
            record(metric, elapsedUs, memoryAfter - memoryBefore, pssKb)
        }
        write(metric, samples)
    }

    private fun record(metric: String, elapsedUs: Long, memoryDeltaBytes: Long, pssKb: Long) {
        val dir = File(context.getExternalFilesDir(null), "r12-perf").apply { mkdirs() }
        File(dir, "samples-$runTag.tsv").appendText(
            "$metric\t$elapsedUs\t$memoryDeltaBytes\t$pssKb\n",
        )
    }

    private fun write(metric: String, samples: List<Long>) {
        require(samples.isNotEmpty()) { "$metric produced no samples" }
        val sorted = samples.sorted()
        val p50 = sorted[(sorted.size - 1) * 50 / 100]
        val p95 = sorted[(sorted.size - 1) * 95 / 100]
        val dir = File(context.getExternalFilesDir(null), "r12-perf").apply { mkdirs() }
        val header = "metric\tsamples\tp50_us\tp95_us\tmax_us\n"
        File(dir, "summary-$runTag.tsv").appendText(
            header.let { if (File(dir, "summary-$runTag.tsv").length() == 0L) it else "" } +
                "$metric\t${samples.size}\t$p50\t$p95\t${sorted.last()}\n",
        )
        File(dir, "$metric-summary.txt").writeText(
            String.format(
                Locale.US,
                "%s: n=%d p50=%dus p95=%dus max=%dus",
                metric, samples.size, p50, p95, sorted.last(),
            ),
        )
    }

    @Composable
    private fun SurfaceUnderMeasure(key: Int, content: @Composable () -> Unit) {
        androidx.compose.runtime.key(key) { content() }
    }

    private fun summaryState(): ArtistEnrichmentUiState = ArtistEnrichmentUiState(
        artistName = "Midnight Oil",
        heading = "Fotografías de 'Midnight Oil'",
        identityConfirmed = true,
        identityLabel = "Identidad musical confirmada",
        identityDetail = "MBID: 1f2e3d4c-5b6a-4798-8c1d-2e3f4a5b6c7d",
        mosaic = ArtistPhotoSlot(
            slot = ArtworkSlot.ARTIST_THUMB,
            title = "Foto de mosaico",
            availability = ArtistPhotoAvailability.AVAILABLE,
            statusLabel = "Disponible",
            imagePath = null,
            attribution = "Charles Millot",
            license = "CC BY 2.0",
        ),
        card = ArtistPhotoSlot(
            slot = ArtworkSlot.ARTIST_HERO,
            title = "Foto de ficha",
            availability = ArtistPhotoAvailability.MANUALLY_CHOSEN,
            statusLabel = "Elegida por ti",
            imagePath = null,
        ),
        primaryAction = ArtistEnrichmentPrimaryAction.ViewPhotos,
        primaryActionLabel = "Ver fotos",
        searchActive = false,
        searchMessage = null,
        coverageRows = emptyList(),
        providerRows = emptyList(),
        diagnosed = true,
        delete = org.michimusic.mobile.artist.EnrichmentDeleteState.Idle,
        persistence = org.michimusic.mobile.artist.ArtistPersistencePresentation(null, false),
        canPersist = true,
        hasAlbumMembership = true,
        albumCount = 3,
    )
}
