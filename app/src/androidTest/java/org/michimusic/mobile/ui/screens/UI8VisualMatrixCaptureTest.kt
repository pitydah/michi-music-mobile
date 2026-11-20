package org.michimusic.mobile.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.screens.PlaylistItem
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.VisualPreferences

/**
 * UI8-G6 visual matrix on the instrumented device.
 *
 * Every image is drawn by Compose on the emulator and read back with `captureToImage`
 * (or `UiAutomation.takeScreenshot` for the album-mode DropdownMenu, which lives in its
 * own window and cannot be captured from the harness node). The harness narrows the
 * logical width; for widths above the physical window the density is reduced so the
 * requested dp width still exists, and the manifest records the effective density.
 *
 * This is emulator evidence of the composables the patch changes. It is not physical
 * certification and it does not replace the user's visual acceptance.
 */
@RunWith(AndroidJUnit4::class)
class UI8VisualMatrixCaptureTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val widths = listOf(320, 360, 393, 412, 430, 600)
    private val scales = listOf(1.0f, 1.5f, 2.0f)

    private val outRoot by lazy { File(context.getExternalFilesDir(null), "ui8-matrix") }
    private val manifest by lazy { File(outRoot, "manifest.tsv") }
    private val baseDensity by lazy { context.resources.displayMetrics.density }
    private val windowWidthDp by lazy {
        context.resources.configuration.screenWidthDp.toFloat().coerceAtLeast(320f)
    }

    private lateinit var lightBanner: String
    private lateinit var darkBanner: String

    @Before
    fun prepare() {
        outRoot.mkdirs()
        if (!manifest.exists()) {
            manifest.writeText("surface\twidthDp\tfontScale\tdensity\tbitmapW\tbitmapH\tcolors\tclickables\tminClickablePx\n")
        }
        lightBanner = bannerFile("light", Color.rgb(242, 237, 228))
        darkBanner = bannerFile("dark", Color.rgb(16, 18, 22))
    }

    private fun bannerFile(name: String, color: Int): String {
        val file = File(context.cacheDir, "ui8-banner-$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            for (y in 0 until 96) for (x in 0 until 96) {
                // A real subject, not a flat swatch: a soft diagonal band so the plate
                // covering it would be visible in the pixels.
                val band = if (abs(x - y) < 18) 0.72f else 1f
                val r = (Color.red(color) * band).toInt()
                val g = (Color.green(color) * band).toInt()
                val b = (Color.blue(color) * band).toInt()
                bitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return Uri.fromFile(file).toString()
    }

    private fun config(widthDp: Int, fontScale: Float): Pair<Float, Float> {
        val density = if (widthDp > windowWidthDp) baseDensity * (windowWidthDp / widthDp) else baseDensity
        return density to fontScale
    }


    private fun captureNode(
        surface: String,
        widthDp: Int,
        fontScale: Float,
        density: Float,
        tag: String = HARNESS,
    ): Bitmap {
        rule.waitForIdle()
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        save(surface, widthDp, fontScale, density, bitmap)
        return bitmap
    }

    private fun save(surface: String, widthDp: Int, fontScale: Float, density: Float, bitmap: Bitmap) {
        val dir = File(outRoot, "${widthDp}dp-fs$fontScale")
        dir.mkdirs()
        FileOutputStream(File(dir, "$surface.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val colors = distinctColors(bitmap)
        val clickables = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val minHeight = clickables.minOfOrNull { it.boundsInRoot.height } ?: 0f
        manifest.appendText(
            "$surface\t$widthDp\t$fontScale\t$density\t${bitmap.width}\t${bitmap.height}\t" +
                "$colors\t${clickables.size}\t${minHeight.toInt()}\n",
        )
        assertTrue("$surface at ${widthDp}dp must have real pixels", bitmap.width > 0 && bitmap.height > 0)
        assertTrue("$surface at ${widthDp}dp must draw content, not a blank surface", colors >= 2)
    }

    private fun distinctColors(bitmap: Bitmap): Int {
        val colors = HashSet<Int>()
        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 12).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                colors.add(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        return colors.size
    }

    private fun luminance(bitmap: Bitmap, fromY: Float, toY: Float, fromX: Float = 0.15f, toX: Float = 0.85f): Double {
        var sum = 0.0
        var count = 0
        val y0 = (bitmap.height * fromY).toInt()
        val y1 = (bitmap.height * toY).toInt()
        val x0 = (bitmap.width * fromX).toInt()
        val x1 = (bitmap.width * toX).toInt()
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val color = bitmap.getPixel(x, y)
            sum += (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** Mean luminance is fragile over a plate that carries white text; the claim is that
     * most of the plate area is dark, not that every glyph-free pixel is. */
    private fun darkFraction(
        bitmap: Bitmap,
        fromY: Float,
        toY: Float,
        fromX: Float,
        toX: Float,
        threshold: Double = 0.35,
    ): Double {
        var dark = 0
        var count = 0
        val y0 = (bitmap.height * fromY).toInt()
        val y1 = (bitmap.height * toY).toInt()
        val x0 = (bitmap.width * fromX).toInt()
        val x1 = (bitmap.width * toX).toInt()
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val color = bitmap.getPixel(x, y)
            val luma = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
            if (luma < threshold) dark++
            count++
        }
        return if (count == 0) 0.0 else dark.toDouble() / count
    }

    /** Median luma of a band. A plate makes almost every pixel equally dark; a scrim only
     * darkens the centre, so the median plus the outer strip distinguish the two. */
    private fun medianLuminance(
        bitmap: Bitmap,
        fromY: Float,
        toY: Float,
        fromX: Float,
        toX: Float,
    ): Double {
        val values = ArrayList<Double>()
        val x0 = (bitmap.width * fromX).toInt()
        val x1 = (bitmap.width * toX).toInt()
        val y0 = (bitmap.height * fromY).toInt()
        val y1 = (bitmap.height * toY).toInt()
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val color = bitmap.getPixel(x, y)
            values += (0.2126 * Color.red(color) + 0.7152 * Color.green(color) +
                0.0722 * Color.blue(color)) / 255.0
        }
        if (values.isEmpty()) return 0.0
        values.sort()
        return values[values.size / 2]
    }

    @Composable
    private fun spotlight(item: PlaylistItem, visual: PlaylistVisualState) {
        PlaylistCatalogSpotlight(
            playlist = item,
            visual = visual,
            onOpen = {},
            actions = {},
            // Production's list path gives the spotlight its intrinsic height, so the
            // card and its banner are exactly as tall as the content column.
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /**
     * A whole-window screenshot needs a live display. The CI emulator can start with
     * the screen off, and Compose draws offscreen captures regardless, so only this
     * path has to wake the device before asking UiAutomation for pixels.
     */
    private fun wakeDisplay() {
        val automation = instrumentation.uiAutomation
        // CI can start the instrumentation with the display asleep. Keeping the
        // display on is a prerequisite for whole-window screenshots.
        runCatching { automation.executeShellCommand("svc power stayon true").close() }
        runCatching {
            automation.executeShellCommand("settings put system screen_off_timeout 1800000").close()
        }
        runCatching { automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close() }
        runCatching { automation.executeShellCommand("wm dismiss-keyguard").close() }
        instrumentation.waitForIdleSync()
    }

    private fun captureWholeWindow(): Bitmap {
        wakeDisplay()
        var last: Bitmap? = null
        repeat(12) {
            last = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
            if (last != null && distinctColors(last) >= 2) return last!!
            SystemClock.sleep(250)
            wakeDisplay()
        }
        return last ?: instrumentation.uiAutomation.takeScreenshot()
    }

    @Test
    fun albumModeMenuAtEveryWidthAndScale() {
        // Wake before the activity launches so Compose has a real hierarchy even
        // when the test process starts with the display asleep.
        wakeDisplay()
        var mode by mutableStateOf(LibraryViewMode.MOSAIC)
        var density by mutableStateOf(baseDensity)
        var scale by mutableStateOf(1f)
        rule.setContent {
            MichiTheme {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = scale)) {
                    // The popup lives in its own window, so the whole-window screenshot
                    // must show the same obsidian canvas the app would show behind it.
                    Box(Modifier.fillMaxSize().background(SurfaceObsidian)) {
                        Box(
                            Modifier
                                .width(200.dp)
                                .height(120.dp)
                                .background(SurfaceObsidian)
                                .padding(12.dp),
                        ) {
                            AlbumViewModeToggle(viewMode = mode, onViewModeChange = { mode = it })
                        }
                    }
                }
            }
        }
        for (width in widths) for (fontScale in scales) {
            val (d, fs) = config(width, fontScale)
            rule.runOnUiThread { density = d; scale = fs }
            rule.onNodeWithTag("library_view_mode_toggle").performClick()
            rule.onNodeWithTag("library_view_mode_menu").assertExists()
            rule.waitForIdle()
            val bitmap = captureWholeWindow()
            val dir = File(outRoot, "${width}dp-fs$fontScale")
            dir.mkdirs()
            FileOutputStream(File(dir, "album_mode_menu.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            manifest.appendText(
                "album_mode_menu\t$width\t$fontScale\t$d\t${bitmap.width}\t${bitmap.height}\t" +
                    "${distinctColors(bitmap)}\t-\t-\n",
            )
            assertTrue("album menu at ${width}dp must draw pixels", distinctColors(bitmap) >= 2)
            // Selecting the current mode closes the menu and leaves the mode unchanged.
            rule.onNodeWithTag("album_mode_option_mosaic").performClick()
            rule.onNodeWithTag("library_view_mode_menu").assertDoesNotExist()
        }
    }

    @Test
    fun spotlightWithArtworkAbsentAtEveryWidthAndScale() {
        var width by mutableStateOf(320)
        var density by mutableStateOf(baseDensity)
        var scale by mutableStateOf(1f)
        val item = PlaylistItem(id = "pl-a", name = "Noche de vinilo", trackCount = 1)
        rule.setContent {
            MichiTheme {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = scale)) {
                    Box(
                        Modifier
                            .width(width.dp)
                            .height(300.dp)
                            .background(SurfaceObsidian)
                            .testTag(HARNESS),
                    ) { spotlight(item, PlaylistVisualState()) }
                }
            }
        }
        for (w in widths) for (fontScale in scales) {
            val (d, fs) = config(w, fontScale)
            rule.runOnUiThread { width = w; density = d; scale = fs }
            val bitmap = captureNode("spotlight_no_art", w, fontScale, d)
            assertTrue("plate must stay local in the artwork-absent case", luminance(bitmap, 0.05f, 0.35f) > 0.02)
        }
    }

    @Test
    fun spotlightCaptionStaysLegibleOverLightArtworkWithoutAPlate() {
        var width by mutableStateOf(320)
        var density by mutableStateOf(baseDensity)
        var scale by mutableStateOf(1f)
        val item = PlaylistItem(id = "pl-light", name = "Mañana clara", trackCount = 12)
        rule.setContent {
            MichiTheme {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = scale)) {
                    Box(
                        Modifier
                            .width(width.dp)
                            .background(SurfaceObsidian)
                            .testTag(HARNESS),
                    ) { spotlight(item, PlaylistVisualState(bannerUri = lightBanner)) }
                }
            }
        }
        for (w in widths) for (fontScale in scales) {
            val (d, fs) = config(w, fontScale)
            rule.runOnUiThread { width = w; density = d; scale = fs }
            rule.waitUntil(10_000) {
                runCatching {
                    val probe = rule.onNodeWithTag(HARNESS).captureToImage().asAndroidBitmap()
                    luminance(probe, 0.02f, 0.20f) > 0.55
                }.getOrDefault(false)
            }
            val bitmap = captureNode("spotlight_light_banner", w, fontScale, d)
            val artwork = luminance(bitmap, 0.02f, 0.20f)
            // Measure where the letters actually are. Sampling the whole caption box mixes
            // the scrim with the part of the radial gradient that has already faded out,
            // which understates the contrast the title really has.
            val frame = rule.onNodeWithTag(HARNESS).fetchSemanticsNode()
            // The card root is clickable, so its children only exist in the unmerged tree.
            val titleNode = rule.onNodeWithTag("playlist_spotlight_title_pl-light", useUnmergedTree = true)
                .fetchSemanticsNode()
            val left = (titleNode.positionInRoot.x - frame.positionInRoot.x) / frame.size.width
            val right = left + titleNode.size.width / frame.size.width.toFloat()
            val top = (titleNode.positionInRoot.y - frame.positionInRoot.y) / frame.size.height
            val bottom = top + titleNode.size.height / frame.size.height.toFloat()
            val caption = medianLuminance(bitmap, top, bottom, left, right)
            // The banner just above the caption must stay bright: the darkening is local to
            // the words, not a heavy scrim over the whole lower half.
            val above = medianLuminance(
                bitmap,
                (top - 0.14f).coerceAtLeast(0.02f),
                (top - 0.03f).coerceAtLeast(0.03f),
                left,
                right,
            )
            val dark = darkFraction(bitmap, top, bottom, left, right)
            println(
                "CAPTION-CONTRAST width=${w}dp fs=$fontScale artwork=$artwork caption=$caption " +
                    "above=$above dark=$dark ratio=${1.05 / (caption + 0.05)}",
            )
            assertTrue("light artwork must remain visible at ${w}dp (luma=$artwork)", artwork > 0.55)
            // White text needs 4.5:1 over its background, i.e. a background luma <= 0.183.
            assertTrue(
                "the white caption needs 4.5:1 at ${w}dp (median=$caption)",
                caption <= 0.183,
            )
            assertTrue(
                "the banner above the caption must stay visible at ${w}dp (above=$above)",
                above > 0.45,
            )
            assertTrue(
                "some dark area must still exist behind the caption at ${w}dp (dark=$dark)",
                dark >= 0.30,
            )
        }
    }

    @Test
    fun spotlightOverDarkArtworkKeepsWhiteCounterLegible() {
        var width by mutableStateOf(393)
        var density by mutableStateOf(baseDensity)
        var scale by mutableStateOf(1f)
        val item = PlaylistItem(id = "pl-dark", name = "Sesión nocturna", trackCount = 20)
        rule.setContent {
            MichiTheme {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = scale)) {
                    Box(
                        Modifier
                            .width(width.dp)
                            .background(SurfaceObsidian)
                            .testTag(HARNESS),
                    ) { spotlight(item, PlaylistVisualState(bannerUri = darkBanner)) }
                }
            }
        }
        val (d, fs) = config(393, 1f)
        rule.runOnUiThread { width = 393; density = d; scale = fs }
        val bitmap = captureNode("spotlight_dark_banner", 393, 1f, d)
        assertTrue("dark artwork must stay dark", luminance(bitmap, 0.02f, 0.20f) < 0.35)
        // Over dark artwork the caption band must be dark on its own: the scrim is not
        // allowed to be the only thing keeping the white counter legible.
        val caption = medianLuminance(bitmap, 0.80f, 0.96f, 0.32f, 0.95f)
        assertTrue(
            "the caption over dark artwork must stay dark (median=$caption)",
            caption <= 0.183,
        )
        rule.onNodeWithTag("playlist_spotlight_title_pl-dark", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("20 canciones", useUnmergedTree = true).assertExists()
    }

    @Test
    fun deckWithTwoPlaylistsAtEveryWidthAndScale() {
        var width by mutableStateOf(320)
        var density by mutableStateOf(baseDensity)
        var scale by mutableStateOf(1f)
        val cards = listOf(
            PlaylistItem(id = "deck-a", name = "Playlist Alfa", trackCount = 2),
            PlaylistItem(id = "deck-b", name = "Playlist Beta", trackCount = 7),
        )
        rule.setContent {
            MichiTheme {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = scale)) {
                    Box(
                        Modifier
                            .width(width.dp)
                            .height(340.dp)
                            .background(SurfaceObsidian)
                            .testTag(HARNESS),
                    ) {
                        PlaylistSwipingDeck(cards = cards, key = { it.id }) { item ->
                            PlaylistCatalogSpotlight(
                                playlist = item,
                                visual = PlaylistVisualState(),
                                onOpen = {},
                                actions = {},
                            )
                        }
                    }
                }
            }
        }
        for (w in widths) for (fontScale in scales) {
            val (d, fs) = config(w, fontScale)
            rule.runOnUiThread { width = w; density = d; scale = fs }
            rule.onNodeWithTag("playlist_swiping_front").assertExists()
            val bitmap = captureNode("deck_two", w, fontScale, d)
            assertTrue("deck at ${w}dp must draw cards", distinctColors(bitmap) >= 2)
        }
    }

    @Test
    fun deckCardCountsAndPreferencesAreReal() {
        var cards by mutableStateOf(listOf<PlaylistItem>())
        var prefs by mutableStateOf(VisualPreferences())
        rule.setContent {
            MichiTheme(visualPreferences = prefs) {
                Box(
                    Modifier
                        .width(393.dp)
                        .height(340.dp)
                        .background(SurfaceObsidian)
                        .testTag(HARNESS),
                ) {
                    PlaylistSwipingDeck(cards = cards, key = { it.id }) { item ->
                        PlaylistCatalogSpotlight(
                            playlist = item,
                            visual = PlaylistVisualState(),
                            onOpen = {},
                            actions = {},
                        )
                    }
                }
            }
        }
        // 0 playlists: the deck owns no card and exposes no front semantics.
        rule.onNodeWithTag("playlist_swiping_front").assertDoesNotExist()
        manifest.appendText("deck_zero\t393\t1.0\t$baseDensity\t-\t-\t-\t0\t0\n")

        cards = List(1) { PlaylistItem(id = "one-$it", name = "Única $it", trackCount = it) }
        rule.waitForIdle()
        rule.onNodeWithTag("playlist_swiping_front").assertExists()
        captureNode("deck_one", 393, 1f, baseDensity)

        cards = List(20) { PlaylistItem(id = "many-$it", name = "Colección $it", trackCount = it * 3) }
        rule.waitForIdle()
        captureNode("deck_twenty", 393, 1f, baseDensity)

        prefs = VisualPreferences(reducedMotion = true)
        rule.waitForIdle()
        captureNode("deck_twenty_reduced_motion", 393, 1f, baseDensity)

        prefs = VisualPreferences(highContrast = true)
        cards = List(2) { PlaylistItem(id = "hc-$it", name = "Alto contraste $it", trackCount = it) }
        rule.waitForIdle()
        captureNode("deck_two_high_contrast", 393, 1f, baseDensity)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun deckOffersBothAccessibleDirectionsWithoutSwipe() {
        var cards by mutableStateOf(
            listOf(
                PlaylistItem(id = "acc-a", name = "Accesible Alfa", trackCount = 1),
                PlaylistItem(id = "acc-b", name = "Accesible Beta", trackCount = 2),
            ),
        )
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                Box(
                    Modifier
                        .width(393.dp)
                        .height(340.dp)
                        .background(SurfaceObsidian)
                        .testTag(HARNESS),
                ) {
                    PlaylistSwipingDeck(cards = cards, key = { it.id }) { item ->
                        PlaylistCatalogSpotlight(
                            playlist = item,
                            visual = PlaylistVisualState(),
                            onOpen = {},
                            actions = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("playlist_swiping_front", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("playlist_swiping_front").performCustomAccessibilityActionWithLabel("Siguiente playlist")
        rule.waitForIdle()
        rule.onNodeWithText("Accesible Beta", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("playlist_swiping_front").performCustomAccessibilityActionWithLabel("Playlist anterior")
        rule.waitForIdle()
        rule.onNodeWithText("Accesible Alfa", useUnmergedTree = true).assertExists()
        assertEquals(2, cards.size)
    }

    private companion object {
        const val HARNESS = "ui8_harness"
    }
}
