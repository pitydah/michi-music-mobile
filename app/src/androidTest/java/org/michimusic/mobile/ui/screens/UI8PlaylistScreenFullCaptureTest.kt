package org.michimusic.mobile.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.PlaylistRepository
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.components.BottomNavBar
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * UI8-KILLCRITIC round 2: the matrix that was missing.
 *
 * The previous round certified components in isolation, which could not prove how the screen
 * actually reads: the whole catalogue Lista with its real header, the compact mode selector,
 * the dock and both modes. This harness renders the real [PlaylistScreen] with real playlists
 * from the app database and real imported banners.
 *
 * The configuration comes from the device, not from a density override. A `Dialog` or a root
 * capture is clamped to the real window, so an in-composition override cannot prove how the
 * screen behaves at 600 dp: the driver sets `wm size` and `font_scale`, and this test captures
 * whatever the window really is. One invocation renders one device configuration and captures
 * both modes.
 */
@RunWith(AndroidJUnit4::class)
class UI8PlaylistScreenFullCaptureTest {

    @get:Rule
    val rule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val outRoot by lazy { File(context.getExternalFilesDir(null), "ui8-playlist-screen") }
    private val manifest by lazy { File(outRoot, "manifest.tsv") }

    @Test
    fun theWholePlaylistScreenIsCapturedInBothModes() {
        outRoot.mkdirs()
        if (!manifest.exists()) {
            manifest.writeText("mode widthDp fontScale density pxW pxH cards selectorWidthDp selectorHeightDp gapDp\n")
        }

        val koin = GlobalContext.get()
        val repository = koin.get<PlaylistRepository>()
        val localMedia = koin.get<LocalMediaRepository>()

        // A deterministic catalogue: whatever the device had before is removed, so the
        // capture counts mean what they say.
        val playlists = runBlocking {
            repository.getAllPlaylists().forEach { repository.deletePlaylist(it.id) }
            (1..20).map { index -> repository.createPlaylist("Playlist %02d".format(index)) }
        }
        val visualStore = PlaylistVisualStore(context)
        // Two playlists get a real imported banner and cover, so the capture is not a
        // bannerless placeholder study. The provider streams a generated PNG through a
        // content:// URI, and the store copies it into the app's private storage, which is
        // exactly the production import path.
        listOf(0 to "blue.png", 1 to "amber.png").forEach { (index, name) ->
            // The importer only accepts content:// URIs, exactly like a real picker result.
            // The provider lives in the test APK, which has its own package and UID.
            val artworkUri = Uri.parse(
                "content://${instrumentation.context.packageName}.artworkprobe/$name",
            )
            val result = runBlocking {
                visualStore.applyVisualEdit(
                    playlists[index].id,
                    PlaylistVisualEditRequest(
                        cover = PlaylistVisualImageChange.Replace(artworkUri),
                        banner = PlaylistVisualImageChange.Replace(artworkUri),
                    ),
                )
            }
            println("ARTWORK-APPLY id=${playlists[index].id} result=$result")
            assertTrue(
                "the banner and cover must really be imported for ${playlists[index].id}, got $result",
                result is PlaylistVisualEditResult.Success,
            )
        }

        val viewModel = PlaylistsViewModel(repository, localMedia, Dispatchers.IO)
        viewModel.loadPlaylists()
        rule.waitUntil(20_000) { viewModel.playlists.value.size == playlists.size }

        // The real configuration of the window, filled in by the composition itself.
        var widthDp = 0
        var fontScale = 0f
        rule.setContent {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            SideEffect {
                widthDp = configuration.screenWidthDp
                fontScale = density.fontScale
            }
            MichiTheme {
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = SurfaceObsidian,
                        bottomBar = {
                            BottomNavBar(
                                currentRoute = "playlist",
                                onTabSelected = {},
                                selectedRoute = "playlist",
                            )
                        },
                    ) { innerPadding ->
                        Box(Modifier.fillMaxSize().padding(innerPadding)) {
                            PlaylistScreen(
                                onNavigateToPlaylist = {},
                                viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }

        rule.waitForIdle()
        val rowsBefore = manifestRows()
        assertEquals("the view mode toggle must be on screen", 1, selectorNodes().size)
        // The toggle keeps its state in rememberSaveable, so the first capture is
        // whatever mode the screen opened in; no extra click is needed to reach Lista.
        // The imported banner is decoded asynchronously by Coil. Wait on the pixels
        // themselves instead of a fixed sleep, so the capture can never claim a banner
        // that had not been drawn yet.
        rule.waitUntil(15_000) { bluePixels(screenshot()) > 500 }
        val listCapture = capture("lista", widthDp, fontScale)

        rule.onNodeWithTag("playlist_view_mode_toggle").performClick()
        rule.waitForIdle()
        val deckCapture = capture("tarjetas", widthDp, fontScale)

        // The banner is real artwork: a blue gradient. The bannerless fallback is graphite,
        // so counting blue pixels proves the imported image was decoded and drawn.
        val blue = bluePixels(listCapture.bitmap)
        println("ARTWORK-PIXELS blue=$blue width=$widthDp fontScale=$fontScale")
        assertTrue("the imported banner must be drawn on the Lista card, blue pixels=$blue", blue > 500)

        val rows = manifestRows()
        assertEquals("manifest must describe both captures", rowsBefore + 2, rows)
        assertEquals("the Tarjetas capture must contain the deck", 1, deckCapture.cards)

        // UI8-KILLCRITIC: Playlists now uses the same single-icon switch as Artists. The
        // target is the transparent 48 dp box, so it is measured on the button itself.
        val toggleNode = rule.onNodeWithTag("playlist_view_mode_toggle").fetchSemanticsNode().boundsInRoot
        val createNode = rule.onAllNodesWithContentDescription("Nueva Playlist").fetchSemanticsNodes().first().boundsInRoot
        val density = context.resources.displayMetrics.density
        val targetDp = toggleNode.height / density
        val gapDp = (createNode.left - toggleNode.right) / density
        println("PLAYLIST-TOGGLE target=${targetDp}dp gap=${gapDp}dp width=${toggleNode.width / density}dp fs=$fontScale")
        assertTrue("the toggle target is ${targetDp}dp tall; 48 dp is required", targetDp >= 47.5f)
        assertTrue("the toggle must sit left of create, got ${gapDp}dp", gapDp > 0f)
        assertTrue("the gap to create is ${gapDp}dp; 8-10 dp expected", gapDp in 7.5f..10.5f)

        val baseline = listOf(toggleNode.left, toggleNode.top, toggleNode.width, toggleNode.height, createNode.left)
        repeat(10) {
            rule.onNodeWithTag("playlist_view_mode_toggle").performClick()
            rule.waitForIdle()
            val moved = rule.onNodeWithTag("playlist_view_mode_toggle").fetchSemanticsNode().boundsInRoot
            val plus = rule.onAllNodesWithContentDescription("Nueva Playlist").fetchSemanticsNodes().first().boundsInRoot
            assertEquals(
                "the header must not move when the mode changes",
                baseline,
                listOf(moved.left, moved.top, moved.width, moved.height, plus.left),
            )
        }
    }

    private fun manifestRows(): Int = manifest.readLines().count { it.isNotBlank() }

    private data class Capture(
        val bitmap: Bitmap,
        val cards: Int,
        val selectorWidthDp: Float,
        val selectorHeightDp: Float,
        val gapDp: Float,
    )

    private fun capture(mode: String, widthDp: Int, fontScale: Float): Capture {
        val bitmap = screenshot()
        val density = context.resources.displayMetrics.density
        val dir = File(outRoot, "${widthDp}dp-fs$fontScale")
        dir.mkdirs()
        FileOutputStream(File(dir, "$mode.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // The front card of the deck is the only one that owns semantics, so this count
        // proves the Tarjetas capture really rendered the deck and the Lista one did not.
        val frontCards = rule.onAllNodesWithTag("playlist_swiping_front").fetchSemanticsNodes().size
        val selectorNodes = selectorNodes()
        assertTrue("the selector must exist in $mode", selectorNodes.isNotEmpty())
        val selector = selectorNodes.first()
        val selectorWidth = selector.size.width / density
        val selectorHeight = selector.size.height / density
        val createButton = rule.onAllNodesWithContentDescription("Nueva Playlist").fetchSemanticsNodes()
        assertTrue("the create button must exist in $mode", createButton.isNotEmpty())
        val gap = (createButton.first().boundsInRoot.left - selector.boundsInRoot.right) / density
        manifest.appendText(
            "$mode\t$widthDp\t$fontScale\t$density\t${bitmap.width}\t${bitmap.height}\t" +
                "$frontCards\t$selectorWidth\t$selectorHeight\t$gap\n",
        )
        println(
            "FULL-SCREEN mode=$mode width=${widthDp}dp fs=$fontScale px=${bitmap.width}x${bitmap.height} " +
                "selector=${selectorWidth}x${selectorHeight}dp gap=${gap}dp cards=$frontCards",
        )
        assertTrue("$mode at ${widthDp}dp must draw pixels", bitmap.width > 0 && bitmap.height > 0)
        assertEquals(
            "only Tarjetas draws a swiping deck at ${widthDp}dp",
            if (mode == "tarjetas") 1 else 0,
            frontCards,
        )
        val createWidth = createButton.first().size.width / density
        assertTrue(
            "the selector and the create button must fit in one row at ${widthDp}dp/fs$fontScale: " +
                "selector=${selectorWidth}dp + gap=${gap}dp + create=${createWidth}dp",
            selectorWidth + gap + createWidth <= widthDp * 0.98f,
        )
        assertTrue("the selector must sit left of the create button, gap=${gap}dp", gap > 0f)
        // UI8-KILLCRITIC: Playlists has no capsule any more, so there is no rail width or
        // height to certify here. The icon toggle's own target and gap are asserted in
        // PLAYLIST-TOGGLE below, which is the contract that replaced the segmented rail.
        assertTrue(
            "the toggle must be a square-ish icon control, got ${selectorWidth}x${selectorHeight}dp",
            selectorWidth <= 64f && selectorHeight >= 47f,
        )
        return Capture(bitmap, frontCards, selectorWidth, selectorHeight, gap)
    }

    private fun selectorNodes() = rule.onAllNodesWithTag("playlist_view_mode_toggle").fetchSemanticsNodes()

    /**
     * A whole-window screenshot needs a live display. Compose can draw an offscreen capture
     * with the screen off, but the full-screen evidence must be what the device shows.
     */
    private fun screenshot(): Bitmap {
        val automation = instrumentation.uiAutomation
        runCatching { automation.executeShellCommand("svc power stayon true").close() }
        runCatching { automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close() }
        runCatching { automation.executeShellCommand("wm dismiss-keyguard").close() }
        repeat(3) { attempt ->
            val bitmap = automation.takeScreenshot()
            if (bitmap != null && !isBlank(bitmap)) return bitmap
            if (attempt < 2) Thread.sleep(400)
        }
        error("the device did not return a real screenshot")
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val first = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        for (y in 0 until bitmap.height step bitmap.height / 8) {
            for (x in 0 until bitmap.width step bitmap.width / 8) {
                if (bitmap.getPixel(x, y) != first) return false
            }
        }
        return true
    }

    /** Counts the strongly blue pixels the blue banner gradient must produce. */
    private fun bluePixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 3) {
            for (x in 0 until bitmap.width step 3) {
                val color = bitmap.getPixel(x, y)
                val red = (color shr 16) and 0xFF
                val blue = color and 0xFF
                if (blue > 90 && blue > red + 40) count++
            }
        }
        return count
    }

}
