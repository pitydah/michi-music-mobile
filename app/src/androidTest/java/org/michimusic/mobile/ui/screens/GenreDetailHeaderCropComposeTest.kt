package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.VisualPreferences
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * UI7 Phase C crop gate for the Editorial genre-detail header.
 *
 * The approved poster bakes its own title, subtitle and frame. The header keeps
 * the live title in its opaque lower band, so the artwork band must never slice
 * through that baked block: a width-led bottom crop on a narrow poster reaches
 * up into it at 320..390dp and leaves partial glyphs (or the pink frame corner)
 * at the top of the band.
 *
 * This gate measures the real header at 320, 360, 390, 411 and 480dp, proves the
 * top of the artwork band is the design-system matte (no artwork pixels), and
 * proves the guard disappears once the crop is already title-safe.
 */
@RunWith(AndroidJUnit4::class)
class GenreDetailHeaderCropComposeTest {
    @get:Rule val rule = createComposeRule()

    private val fixture by lazy { genreLayoutFixture() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidenceEnabled =
        InstrumentationRegistry.getArguments().getString("michiGenreEvidence") == "true"

    /** Approved geometry of genre_pop_editorial.webp (514x268). */
    private val popRatio = 514f / 268f

    private val panelWidth = mutableIntStateOf(320)
    private val panelFontScale = mutableFloatStateOf(1f)
    private val panelDensity = mutableFloatStateOf(2f)

    private val evidenceDir: File? = if (evidenceEnabled) {
        File(context.getExternalFilesDir(null), "ui7-mega-c-crop").apply { mkdirs() }
    } else null
    private val evidenceLines = mutableListOf<String>()

    private fun pxOf(dpValue: Float): Float = dpValue * panelDensity.value

    private fun bounds(tag: String, unmergedTree: Boolean = false): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = unmergedTree)
            .fetchSemanticsNode().boundsInRoot

    private fun exists(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun waitForTag(tag: String) {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** One content tree; width/font scale/density are state so all widths fit one test. */
    private fun setPanelOnce() {
        val genres = fixture.genres
        val playableIds = fixture.tracks.map { it.id }.toSet()
        rule.setContent {
            val widthDp = panelWidth.intValue
            val fontScale = panelFontScale.floatValue
            val density = panelDensity.floatValue
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(baseConfiguration, widthDp, fontScale) {
                Configuration(baseConfiguration).apply {
                    screenWidthDp = widthDp
                    this.fontScale = fontScale
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    // Production hosts this panel on MainActivity's
                    // `Surface(color = SurfaceObsidian)`. The header clips to a
                    // 20dp rounded rect, so without the same surface the
                    // corner cut-outs expose the test window's light default
                    // and the cropped capture would measure the harness
                    // background instead of the artwork band.
                    Box(
                        Modifier.width(widthDp.dp).fillMaxHeight()
                            .background(SurfaceObsidian),
                    ) {
                        GenreLibraryPanel(
                            genres = genres,
                            playableTrackIds = playableIds,
                            currentTrack = null,
                            isPlaying = false,
                            onPlay = { _, _ -> },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun openEditorialPop() {
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("genre_view_editorial").performClick()
        rule.waitForIdle()
        waitForTag(editorialTileTag("Pop"))
        rule.onNodeWithTag(editorialTileTag("Pop")).performClick()
        waitForTag("genre_detail_header_poster_pop")
        rule.waitForIdle()
    }

    private fun luminance(bitmap: Bitmap, x: Int, y: Int): Float {
        val pixel = bitmap.getPixel(x, y)
        return 0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) +
            0.0722f * Color.blue(pixel)
    }

    /**
     * The brightest pixel in the top guard strip, sampled away from the
     * "Géneros" pill. A title-safe header only has the opaque matte there, so
     * the value stays near the obsidian surface; an unguarded crop shows the
     * poster's baked subtitle or frame and reports a bright value.
     */
    private fun brightestGuardPixel(header: Bitmap, guardPx: Int): Float {
        if (guardPx <= 3) return 0f
        val x0 = (header.width * 0.55f).toInt()
        val y1 = (guardPx - 2).coerceAtMost(header.height - 1)
        var brightest = 0f
        var y = 1
        while (y <= y1) {
            var x = x0
            while (x < header.width) {
                brightest = maxOf(brightest, luminance(header, x, y))
                x += 3
            }
            y += 2
        }
        return brightest
    }

    private fun publish(target: File, remoteName: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(
                "mkdir -p /data/local/tmp/michi-evidence; " +
                    "cp '${target.absolutePath}' '/data/local/tmp/michi-evidence/$remoteName'",
            )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun capture(name: String): Bitmap {
        val bitmap = rule.onNodeWithTag("genre_detail_header").captureToImage().asAndroidBitmap()
        val directory = evidenceDir
        if (directory != null) {
            val target = File(directory, "$name.png")
            FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            publish(target, "crop-$name.png")
        }
        println("CROP_EVIDENCE $name ${bitmap.width}x${bitmap.height}")
        return bitmap
    }

    private data class HeaderMetrics(
        val label: String,
        val header: Rect,
        val artwork: Rect,
        val guardDp: Float,
        val visibleTop: Float,
        val brightest: Float,
        val matte: Rect?,
    )

    private fun measureWidth(width: Int, fontScaleLabel: String): HeaderMetrics {
        val artwork = bounds("genre_detail_header_artwork")
        val header = bounds("genre_detail_header")
        val bandWidthDp = artwork.width / panelDensity.value
        val bandHeightDp = artwork.height / panelDensity.value
        val expectedGuardDp = editorialTitleGuardDp(popRatio, bandWidthDp, bandHeightDp)
        val label = "panel=${width}dp f=$fontScaleLabel"
        val screenshot = capture("editorial-pop-${width}dp-f$fontScaleLabel")
        val brightest = brightestGuardPixel(screenshot, pxOf(expectedGuardDp).toInt())
        val matte = if (exists("genre_detail_header_title_matte")) {
            bounds("genre_detail_header_title_matte", unmergedTree = true)
        } else {
            null
        }
        val metrics = HeaderMetrics(
            label = label,
            header = header,
            artwork = artwork,
            guardDp = expectedGuardDp,
            visibleTop = editorialVisibleTopFraction(popRatio, bandWidthDp, bandHeightDp),
            brightest = brightest,
            matte = matte,
        )
        evidenceLines += "$label header=$header artwork=$artwork " +
            "guard=${expectedGuardDp}dp brightest=$brightest matte=${matte?.height}"
        return metrics
    }

    private fun assertTitleSafe(metrics: HeaderMetrics) {
        val label = metrics.label
        assertTrue(
            "$label first artwork row=${metrics.visibleTop} must stay below the baked " +
                "title floor $EDITORIAL_TITLE_ZONE_FRACTION",
            metrics.visibleTop >= EDITORIAL_TITLE_ZONE_FRACTION,
        )
        assertTrue(
            "$label guard=${metrics.guardDp}dp must not expose artwork or baked title " +
                "pixels (brightest=${metrics.brightest})",
            metrics.brightest < 70f,
        )
        if (metrics.guardDp > 0.5f) {
            val matte = metrics.matte
            assertTrue("$label must draw the title matte", matte != null)
            requireNotNull(matte)
            assertTrue("$label matte starts at the artwork top",
                abs(matte.top - metrics.artwork.top) <= pxOf(1f))
            assertTrue("$label matte=${matte.height}px must cover ${pxOf(metrics.guardDp)}px",
                abs(matte.height - pxOf(metrics.guardDp)) <= pxOf(1.5f))
            assertTrue("$label matte must stay inside the artwork band",
                matte.bottom <= metrics.artwork.bottom + pxOf(1f))
            assertTrue("$label matte must stay inside the header",
                matte.bottom <= metrics.header.bottom)
        } else {
            assertTrue("$label is already title-safe: no matte may be drawn",
                metrics.matte == null)
        }
    }

    @Test fun editorialHeaderNeverSlicesTheBakedTitleAtPhoneWidths() {
        setPanelOnce()
        openEditorialPop()
        val results = listOf(320, 360, 390, 411, 480).map { width ->
            // 2.0 density keeps a real 480dp panel inside the 1080px emulator
            // and gives every width the same px-per-dp conversion.
            panelWidth.intValue = width
            panelDensity.floatValue = 2f
            rule.waitForIdle()
            rule.onNodeWithTag("genre_detail_header").assertExists()
            measureWidth(width, "1.0")
        }
        writeEvidence("widths-f10")
        results.forEach(::assertTitleSafe)
    }

    @Test fun editorialHeaderKeepsTheGuardAcrossFontScales() {
        setPanelOnce()
        openEditorialPop()
        val results = mutableListOf<HeaderMetrics>()
        listOf(1.3f, 2.0f).forEach { fontScale ->
            listOf(320, 360, 390, 411, 480).forEach { width ->
                panelWidth.intValue = width
                panelDensity.floatValue = 2f
                panelFontScale.floatValue = fontScale
                rule.waitForIdle()
                results += measureWidth(width, fontScale.toString())
            }
        }
        writeEvidence("font-scales")
        results.forEach(::assertTitleSafe)
    }

    @Test fun mosaicoHeaderNeverGainsAnEditorialMatte() {
        setPanelOnce()
        panelWidth.intValue = 320
        rule.waitForIdle()
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("genre_view_featured").performClick()
        rule.waitForIdle()
        waitForTag(mosaicTileTag("Pop"))
        rule.onNodeWithTag(mosaicTileTag("Pop")).performClick()
        waitForTag("genre_detail_header_mosaico_pop")
        rule.waitForIdle()
        assertTrue("Mosaico keeps its approved crop",
            !exists("genre_detail_header_title_matte"))
        capture("mosaico-pop-320dp-f1.0")
        writeEvidence("mosaico")
    }

    /** One file per test method: the three gates no longer overwrite each other. */
    private fun writeEvidence(name: String) {
        val directory = evidenceDir ?: return
        val boundsFile = File(directory, "crop-bounds-$name.txt")
        boundsFile.writeText(evidenceLines.joinToString("\n"))
        publish(boundsFile, "crop-bounds-$name.txt")
    }
}
