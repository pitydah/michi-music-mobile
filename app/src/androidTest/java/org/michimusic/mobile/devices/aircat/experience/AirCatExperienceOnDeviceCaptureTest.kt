package org.michimusic.mobile.devices.aircat.experience

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeAuthority
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeSource
import org.michimusic.mobile.devices.aircat.AirCatTransferState
import org.michimusic.mobile.ui.components.QrScannerDialog
import org.michimusic.mobile.ui.components.QrScannerPurpose
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian

private const val CAPTURE_FRAME = "a71_capture_frame"
private const val CAPTURE_HOME_ID = "home-authority-identifier"
private const val CAPTURE_PINNED_URL = "https://home.example:8443"

private enum class Screen {
    HUB, SOURCES, CATALOGUE, REVIEW, TRANSFER, RESULT, TRUST, TRUST_CONFIRM;

    fun airCatState(): AirCatExperienceState = when (this) {
        HUB -> AirCatExperienceState(phase = AirCatPhase.HUB)
        SOURCES -> AirCatExperienceState(
            phase = AirCatPhase.SOURCES,
            sources = listOf(captureSource("dev-1"), captureSource("dev-2", canExplore = false)),
        )
        CATALOGUE -> captureCatalogueState()
        REVIEW -> captureCatalogueState().copy(
            phase = AirCatPhase.REVIEW,
            selection = setOf("rem-1"),
            availableBytes = 4L * 1024 * 1024 * 1024,
        )
        TRANSFER -> captureCatalogueState().copy(
            phase = AirCatPhase.TRANSFER,
            selection = setOf("rem-1"),
            transfer = AirCatTransferUi(
                transferId = "transfer-1",
                state = AirCatTransferState.TRANSFERRING,
                completedTracks = 1,
                totalTracks = 2,
                transferredBytes = 120L * 1024 * 1024,
                totalBytes = 240L * 1024 * 1024,
                currentTrackTitle = "rem-2",
                paused = false,
                error = null,
            ),
        )
        RESULT -> AirCatExperienceState(
            phase = AirCatPhase.RESULT,
            result = AirCatResultUi(
                kind = AirCatResultUi.Kind.COMPLETED,
                title = "Música recibida",
                detail = "2 canciones incorporadas a la biblioteca.",
                tracks = 2,
                bytes = 240L * 1024 * 1024,
            ),
        )
        TRUST, TRUST_CONFIRM -> AirCatExperienceState(phase = AirCatPhase.HUB)
    }

    override fun toString(): String = name.lowercase()
}

private fun captureSource(id: String, canExplore: Boolean = true) = AirCatSourceUi(
    deviceId = id,
    name = "Source $id",
    kindLabel = "Servidor de música",
    discovered = false,
    known = true,
    connected = canExplore,
    homeEnrolled = canExplore,
    airCatAdvertised = canExplore,
)

private fun captureTrack(id: String, album: String) = AirCatTrackUi(
    remoteTrackId = id,
    logicalTrackId = "logical-$id",
    title = id,
    artist = "Artist",
    album = album,
    format = "flac",
    size = 120L * 1024 * 1024,
    sha256 = "a".repeat(64),
)

private fun captureCatalogueState() = AirCatExperienceState(
    phase = AirCatPhase.CATALOGUE,
    selectedSourceId = "dev-1",
    sources = listOf(captureSource("dev-1")),
    catalogue = AirCatCatalogueUi(
        sourceDeviceId = "dev-1",
        sourceName = "Micro Server",
        tracks = listOf(captureTrack("rem-1", "Album A"), captureTrack("rem-2", "Album A")),
    ),
)

private fun captureTrustState(pending: Boolean): MichiLinkTrustState {
    val source = AirCatEnrolledHomeSource(
        sourceMichiId = "source-a",
        homeId = CAPTURE_HOME_ID,
        pinnedBaseUrl = CAPTURE_PINNED_URL,
        tlsSpkiSha256 = "b".repeat(64),
    )
    val state = MichiLinkTrustState(
        loading = false,
        homes = listOf(
            AirCatEnrolledHomeAuthority(
                homeId = CAPTURE_HOME_ID,
                authorityMichiId = CAPTURE_HOME_ID,
                authorityPublicKeyBase64Url = "authority-public-key",
                sourceMichiIds = listOf("source-a"),
                sourceBindingsSha256 = "c".repeat(64),
            ),
        ),
        sources = listOf(source),
    )
    return if (pending) state.copy(pending = MichiLinkTrustPending.Source(source)) else state
}

/**
 * A71-S7 visual + touch-target certification on real Android.
 *
 * Every screen of the AirCat hub — and both states of the trust surface — is
 * rendered at the four widths and four font scales the acceptance contract
 * names, and the pixels Compose actually drew are saved as PNGs. The test also
 * asserts, per configuration:
 * 1. the composition measured the requested width, so a 600 dp case is not a
 *    clamped 411 dp measurement wearing a 600 dp label;
 * 2. every clickable node is at least 48 dp tall;
 * 3. the bitmap is not blank (three or more sampled colours).
 *
 * The emulator window is 411 dp wide. For the 600 dp cases the composition
 * density is lowered to `windowWidthDp * baseDensity / 600` so the requested
 * width physically fits, which is what a 600 dp device with that density would
 * measure. This is stated in the report; it is not presented as a tablet
 * capture.
 *
 * There is no real Michi peer in this environment, so catalogue, review,
 * transfer and result are captured from their real production composables with
 * explicit state, not from a live peer session. EXTERNAL_INTEROP stays
 * BLOCKED_EXTERNAL.
 *
 * The Home administration QR is the ninth surface. It is a `Dialog`, so it is
 * captured by [theHomeAdministrationQrIsCapturedAtEveryPromisedWidthAndScale]
 * from its own window; see that test for why its measured width is recorded
 * instead of asserted against the dp column.
 */
@RunWith(AndroidJUnit4::class)
class AirCatExperienceOnDeviceCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val widths = listOf(360, 393, 430, 600)
    private val scales = listOf(1.0f, 1.3f, 1.6f, 2.0f)

    @Test
    fun everyScreenIsCapturedAtEveryPromisedWidthAndScale() {
        var screen by mutableStateOf(Screen.HUB)
        var widthDp by mutableStateOf(widths.first())
        var scale by mutableStateOf(scales.first())

        compose.setContent {
            val baseDensity = LocalDensity.current.density
            val windowWidthDp = LocalConfiguration.current.screenWidthDp
            val effectiveDensity = minOf(baseDensity, windowWidthDp * baseDensity / widthDp)
            CompositionLocalProvider(LocalDensity provides Density(effectiveDensity, scale)) {
                MichiTheme {
                    Box(Modifier.fillMaxSize().background(SurfaceObsidian)) {
                        Box(
                            Modifier.requiredWidth(widthDp.dp)
                                .fillMaxHeight()
                                .background(SurfaceObsidian)
                                .testTag(CAPTURE_FRAME),
                        ) {
                            when (screen) {
                                Screen.TRUST -> MichiLinkTrustContent(
                                    state = captureTrustState(pending = false),
                                    onIntent = {},
                                    onLinkHome = {},
                                    onClose = {},
                                )
                                Screen.TRUST_CONFIRM -> MichiLinkTrustContent(
                                    state = captureTrustState(pending = true),
                                    onIntent = {},
                                    onLinkHome = {},
                                    onClose = {},
                                )
                                else -> AirCatExperienceContent(
                                    state = screen.airCatState(),
                                    onIntent = {},
                                )
                            }
                        }
                    }
                }
            }
        }

        val baseDensity = context.resources.displayMetrics.density
        val windowWidthDp = context.resources.configuration.screenWidthDp
        val directory = File(context.getExternalFilesDir(null), "a71-s7").apply { mkdirs() }
        val manifest = StringBuilder()
        var captures = 0

        for (target in Screen.entries) {
            for (width in widths) {
                for (fontScale in scales) {
                    compose.runOnIdle {
                        screen = target
                        widthDp = width
                        scale = fontScale
                    }
                    compose.waitForIdle()

                    val effectiveDensity = minOf(baseDensity, windowWidthDp * baseDensity / width)
                    val frame = compose.onNodeWithTag(CAPTURE_FRAME).fetchSemanticsNode()
                    val expectedPx = (width * effectiveDensity).roundToInt()
                    assertTrue(
                        "$target at ${width}dp/${fontScale}x measured " +
                            "${frame.size.width}px, expected ~${expectedPx}px",
                        abs(frame.size.width - expectedPx) <= 3,
                    )

                    val clickables = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                    assertTrue(
                        "$target at ${width}dp/${fontScale}x produced no clickable node",
                        clickables.isNotEmpty(),
                    )
                    clickables.forEach { node ->
                        val heightDp = node.size.height / effectiveDensity
                        val identity = node.config.toString().take(1000)
                        assertTrue(
                            "$target at ${width}dp/${fontScale}x: clickable ${identity} is " +
                                "${heightDp}dp tall, below the 48dp target",
                            heightDp + 0.5f >= 48f,
                        )
                    }

                    val bitmap = compose.onNodeWithTag(CAPTURE_FRAME).captureToImage().asAndroidBitmap()
                    val colors = sampledColors(bitmap)
                    assertTrue(
                        "$target at ${width}dp/${fontScale}x drew only $colors sampled colours",
                        colors >= 3,
                    )
                    val name = "${target}_w${width}_fs${fontScale}.png"
                    val file = File(directory, name)
                    FileOutputStream(file).use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    assertTrue("empty capture: ${file.absolutePath}", file.length() > 0)
                    manifest.appendLine(
                        "$target\t$width\t$fontScale\t${bitmap.width}x${bitmap.height}\t" +
                            "${file.absolutePath}",
                    )
                    // A71-S9: text that loses words behind an ellipsis at 200% is broken
                    // content, not a styling detail. TextLayoutResult.didOverflowWidth is
                    // not the signal here: it compares the node width (337px) against a
                    // paragraph laid out at the full 729px constraint, so it fires on text
                    // that renders complete. didExceedMaxLines is the layout's own verdict
                    // that words were dropped, so a hopeful maxLines bump cannot fake it.
                    val headerTexts = compose.onAllNodes(
                        hasAnyAncestor(hasTestTag("aircat_aero_header")) and
                            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                    ).fetchSemanticsNodes()
                    headerTexts.forEach { textNode ->
                        val layouts = mutableListOf<TextLayoutResult>()
                        textNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                        val layout = layouts.firstOrNull()
                        val paragraph = layout?.let { "${it.multiParagraph.width}x${it.multiParagraph.height}" }
                        val detail = "size=${layout?.size} lines=${layout?.lineCount} " +
                            "paragraph=$paragraph overflowW=${layout?.didOverflowWidth} " +
                            "overflowH=${layout?.didOverflowHeight} " +
                            "maxLines=${layout?.layoutInput?.maxLines} " +
                            "constraints=${layout?.layoutInput?.constraints}"
                        assertFalse(
                            "$target at ${width}dp/${fontScale}x: header text " +
                                "\"${layout?.layoutInput?.text}\" was cut off ($detail)",
                            layout?.multiParagraph?.didExceedMaxLines == true,
                        )
                    }

                    captures++
                    bitmap.recycle()
                }
            }
        }

        File(directory, "manifest.tsv").writeText(
            "screen\twidthDp\tfontScale\tpixels\tpath\n" + manifest,
        )
        println("A71_S7_DEVICE_CAPTURES captures=$captures dir=${directory.absolutePath}")
    }

    /**
     * A71-S7 ninth surface: the Home administration QR dialog.
     *
     * This surface is a `Dialog`, so Compose composes it into its own window. Two consequences
     * are recorded here rather than papered over:
     *
     * 1. The window is sized and densified by the platform from the real display, so the
     *    `LocalDensity` override the eight inline surfaces use never reaches this content. A
     *    first attempt drove the 4x4 matrix that way and produced sixteen byte-identical PNGs;
     *    the duplicate hash is what exposed it. The matrix for this surface is therefore driven
     *    by the device itself (`wm size` for the width, `settings put system font_scale` for the
     *    scale), and every run captures the one configuration the device is really in.
     * 2. The dialog is never asked to reflow to an arbitrary dp width, because the platform
     *    dialog container always picks the width. What is certified for each real configuration
     *    is that the dialog fits the display, that every action keeps the 48 dp touch target,
     *    and that the frame carries real drawn pixels.
     *
     * The file name carries the measured configuration, so a set of captures cannot silently
     * collapse into duplicates without the names colliding too.
     */
    @Test
    fun theHomeAdministrationQrIsCapturedAtTheRealDeviceConfiguration() {
        compose.setContent {
            MichiTheme {
                QrScannerDialog(
                    onScanSuccess = {},
                    onDismiss = {},
                    purpose = QrScannerPurpose.HOME_ADMIN,
                )
            }
        }
        compose.waitForIdle()

        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        val density = metrics.density
        val widthDp = configuration.screenWidthDp
        val fontScale = configuration.fontScale
        val scaleLabel = String.format(Locale.US, "%.1f", fontScale)

        compose.onNodeWithTag("qr_scanner_dialog").assertIsDisplayed()
        // A71-S9: the close action is a contract, not a decoration. A title without weight
        // pushes it outside the row, where the scroll viewport clips it out of sight.
        compose.onNodeWithContentDescription("Cerrar").assertIsDisplayed()

        val node = compose.onNodeWithTag("qr_scanner_dialog").fetchSemanticsNode()
        assertTrue(
            "QR dialog at ${widthDp}dp/${scaleLabel}x measured " +
                "${node.size.width}x${node.size.height}px, beyond the " +
                "${metrics.widthPixels}x${metrics.heightPixels}px display",
            node.size.width <= metrics.widthPixels && node.size.height <= metrics.heightPixels,
        )

        val clickables = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(
            "QR dialog at ${widthDp}dp/${scaleLabel}x produced no clickable node",
            clickables.isNotEmpty(),
        )
        clickables.forEach { clickable ->
            val heightDp = clickable.size.height / density
            val identity = clickable.config.toString().take(1000)
            assertTrue(
                "QR dialog at ${widthDp}dp/${scaleLabel}x: clickable $identity is " +
                    "${heightDp}dp tall, below the 48dp target",
                heightDp + 0.5f >= 48f,
            )
        }

        val bitmap = compose.onNodeWithTag("qr_scanner_dialog").captureToImage().asAndroidBitmap()

        val directory = File(context.getExternalFilesDir(null), "a71-s7-qr").apply { mkdirs() }
        val manifest = File(directory, "manifest.tsv")
        if (!manifest.exists()) {
            manifest.writeText(
                "widthDp\tfontScale\tdensity\tmeasuredPx\tbitmapPx\tstate\tpath\n",
            )
        }
        val prefix = "qr_admin_w${widthDp}_fs${scaleLabel}"
        saveCapture(
            directory = directory,
            manifest = manifest,
            name = "$prefix.png",
            state = "top",
            node = node,
            bitmap = bitmap,
            widthDp = widthDp,
            scaleLabel = scaleLabel,
            density = density,
        )

        // The content scrolls once it is taller than the dialog window, so the action has to be
        // proved reachable at every configuration instead of assumed visible when it opens.
        compose.onNodeWithTag("pairing_link_field").performScrollTo().assertIsDisplayed()
        val confirm = compose.onNodeWithTag("home_admin_qr_confirm").performScrollTo()
        confirm.assertIsDisplayed()
        // Visible is not the same as fully reachable: a node can report a size and still be
        // half outside the surface. The confirmation action must end up entirely inside it.
        val confirmBounds = confirm.fetchSemanticsNode().boundsInRoot
        val surfaceBounds = compose.onNodeWithTag("qr_scanner_dialog")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "QR dialog at ${widthDp}dp/${scaleLabel}x: confirm action $confirmBounds is not " +
                "fully inside the dialog $surfaceBounds after scrolling it into view",
            confirmBounds.left >= surfaceBounds.left &&
                confirmBounds.top >= surfaceBounds.top &&
                confirmBounds.right <= surfaceBounds.right &&
                confirmBounds.bottom <= surfaceBounds.bottom,
        )
        val scrolled = compose.onNodeWithTag("qr_scanner_dialog")
            .captureToImage()
            .asAndroidBitmap()
        saveCapture(
            directory = directory,
            manifest = manifest,
            name = "$prefix-bottom.png",
            state = "bottom",
            node = node,
            bitmap = scrolled,
            widthDp = widthDp,
            scaleLabel = scaleLabel,
            density = density,
        )
        println(
            "A71_S7_QR_CAPTURE widthDp=$widthDp fontScale=$scaleLabel " +
                "px=${node.size.width}x${node.size.height} dir=${directory.absolutePath}",
        )
    }

    /** Writes one QR capture plus its manifest row; a blank or zero-byte frame fails. */
    private fun saveCapture(
        directory: File,
        manifest: File,
        name: String,
        state: String,
        node: SemanticsNode,
        bitmap: Bitmap,
        widthDp: Int,
        scaleLabel: String,
        density: Float,
    ) {
        val colors = sampledColors(bitmap)
        assertTrue(
            "QR dialog at ${widthDp}dp/${scaleLabel}x ($state) drew only $colors sampled colours",
            colors >= 3,
        )
        val file = File(directory, name)
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("empty capture: ${file.absolutePath}", file.length() > 0)
        manifest.appendText(
            "$widthDp\t$scaleLabel\t$density\t${node.size.width}x${node.size.height}\t" +
                "${bitmap.width}x${bitmap.height}\t$state\t${file.absolutePath}\n",
        )
        bitmap.recycle()
    }

    /** Samples a fixed grid so a blank or single-colour frame cannot pass. */
    private fun sampledColors(bitmap: Bitmap): Int {
        val colors = HashSet<Int>()
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                colors += bitmap.getPixel(x, y)
                y += 17
            }
            x += 17
        }
        return colors.size
    }
}
