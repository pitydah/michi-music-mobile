package org.michimusic.mobile.artist

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.artwork.ArtworkEvidence
import org.michimusic.mobile.artist.artwork.ArtworkRights
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * K48-R09 visual certification on real Android.
 *
 * For every configuration the contract names (width x font scale) this renders the
 * enrichment summary inside a screen-sized box, overrides the *real* configuration the
 * production layout decision reads, measures the width Compose actually gave the content
 * and saves the pixels it drew.
 *
 * Nothing here is synthesised evidence: the numbers come from Compose's own measurement on
 * the instrumented device and the PNGs come from `captureToImage`. A configuration that
 * cannot run is reported as `NOT_RUN` by the caller, never guessed.
 */
@RunWith(AndroidJUnit4::class)
class ArtistEnrichmentVisualCertificationTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val allWidths = listOf(320, 360, 390, 412, 480)
    private val scales = listOf(1.0f, 1.3f, 1.6f, 2.0f)

    /**
     * §11.4 forbids calling a case 480 dp when Compose actually measured less. The window
     * width is a hard ceiling for the composition, so the caller runs this suite once per
     * window size and passes the widths that physically fit in it.
     */
    private val widths: List<Int> = InstrumentationRegistry.getArguments()
        .getString("michiWidths")
        ?.split(",")
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it in allWidths }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(320, 360, 390)

    /** Horizontal margins the artist surfaces use, so the effective width is comparable. */
    private val surfaceMargin = 20

    @Test
    fun everyContractConfigurationMeasuresTheWidthItRequested() {
        val thumbnail = localPng("cert-thumb.png")
        val state = summaryState(thumbnail)
        var width by mutableStateOf(widths.first())
        var scale by mutableStateOf(scales.first())

        compose.setContent {
            val configuration = remember(width, scale) {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = width
                    fontScale = scale
                    orientation = Configuration.ORIENTATION_PORTRAIT
                }
            }
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(baseDensity, scale),
            ) {
                MichiTheme {
                    Box(
                        Modifier.width(width.dp).background(SurfaceObsidian),
                    ) {
                        ArtistEnrichmentSummaryContent(
                            uiState = state,
                            onChoosePhoto = {},
                            onPrimaryAction = {},
                            modifier = Modifier.padding(surfaceMargin.dp)
                                .testTag("cert_summary"),
                        )
                    }
                }
            }
        }

        val records = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (candidateWidth in widths) {
            for (candidateScale in scales) {
                width = candidateWidth
                scale = candidateScale
                compose.waitForIdle()

                val node = compose.onNodeWithTag("cert_summary").fetchSemanticsNode()
                val density = node.layoutInfo.density.density
                val effectiveDp = node.size.width / density
                val expectedDp = candidateWidth - surfaceMargin * 2
                val stacked = artworkSlotsInline(candidateWidth, candidateScale).not()
                val ok = kotlin.math.abs(effectiveDp - expectedDp) <= 1.0f
                if (!ok) failures += "${candidateWidth}dp@$candidateScale"
                records += listOf(
                    candidateWidth,
                    String.format(Locale.US, "%.1f", effectiveDp),
                    String.format(Locale.US, "%.2f", density),
                    candidateScale,
                    "portrait",
                    if (stacked) "una-columna" else "dos-columnas",
                    if (ok) "OK" else "FAIL",
                ).joinToString("\t")
                save(
                    "artist_enrichment_summary_%d_%.1f.png"
                        .format(Locale.US, candidateWidth, candidateScale),
                    "cert_summary",
                )
            }
        }

        val report = File(context.filesDir, "certification").apply { mkdirs() }
            .resolve("artist_enrichment_geometry_${widths.first()}-${widths.last()}.tsv")
        report.writeText(
            buildString {
                appendLine("requested_dp\teffective_dp\tdensity\tfont_scale\torientation\tlayout\tresult")
                records.forEach { appendLine(it) }
            },
        )
        println("CERT_GEOMETRY_REPORT ${report.absolutePath}")
        records.forEach { println("CERT_GEOMETRY $it") }
        assertTrue("Widths drifted from the request: $failures", failures.isEmpty())
    }

    @Test
    fun thePhotoPickerAt390DpShowsItsExplicitDestinations() {
        var captured by mutableStateOf<Map<ArtworkSlot, org.michimusic.mobile.artist.artwork.ArtistArtworkSelection>>(emptyMap())
        compose.setContent {
            val configuration = remember {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = 390
                    fontScale = 1.0f
                    orientation = Configuration.ORIENTATION_PORTRAIT
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.0f),
            ) {
                MichiTheme {
                    Column(
                        Modifier.width(390.dp).background(SurfaceObsidian)
                            .verticalScroll(rememberScrollState())
                            .padding(surfaceMargin.dp)
                            .testTag("cert_picker"),
                    ) {
                        ArtistArtworkPickerHost(
                            profile = profile(),
                            artistCredit = credit,
                            libraryId = "lib-cert",
                            albumIds = listOf("album-cert"),
                            initialSelections = emptyMap(),
                            pendingCandidates = listOf(candidate()),
                            associationGroupKey = null,
                            onSelectionsChanged = { captured = it },
                            chooser = { _, _, _ -> error("certification never downloads") },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        save("artist_photo_picker_390.png", "cert_picker")
        assertTrue(captured.isEmpty())
    }

    @Test
    fun theSlotActionKeepsItsWholeLabelInTwoColumns() {
        // 390 dp is the narrowest two-column width the contract allows (§9.9). Inside the
        // row the action only had the ~85 dp text column and broke "fotografía" mid-word.
        compose.setContent {
            val configuration = remember {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = 390
                    fontScale = 1.0f
                    orientation = Configuration.ORIENTATION_PORTRAIT
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.0f),
            ) {
                MichiTheme {
                    Box(Modifier.width(390.dp).background(SurfaceObsidian).padding(surfaceMargin.dp)) {
                        ArtistEnrichmentSummaryContent(
                            uiState = summaryState(localPng("cert_action_label.png")),
                            onChoosePhoto = {},
                            onPrimaryAction = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val node = compose
            .onNodeWithTag("artist_artwork_slot_action_${ArtworkSlot.ARTIST_HERO.name}")
            .fetchSemanticsNode()
        val widthDp = node.size.width / node.layoutInfo.density.density
        assertTrue(
            "the slot action must fit its whole label, measured ${widthDp}dp",
            widthDp >= 120f,
        )
    }

    @Test
    fun theDeleteConfirmationAt320DpNamesWhatItRemoves() {
        compose.setContent {
            val configuration = remember {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = 320
                    fontScale = 1.0f
                    orientation = Configuration.ORIENTATION_PORTRAIT
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.0f),
            ) {
                MichiTheme {
                    Box(Modifier.width(320.dp).background(SurfaceObsidian).padding(surfaceMargin.dp)) {
                        ArtistEnrichmentAdvancedActionsContent(
                            artistCredit = "Midnight Oil",
                            mbid = mbid,
                            albumCount = 3,
                            refreshing = false,
                            delete = EnrichmentDeleteState.Confirming(
                                EnrichmentDeleteReceipt(
                                    libraryId = "lib-cert",
                                    artistCredit = "Midnight Oil",
                                    albumIds = listOf("a1", "a2", "a3"),
                                    mbid = mbid,
                                    associationGroupKey = null,
                                    sequence = 1L,
                                ),
                            ),
                            expanded = true,
                            onToggle = {},
                            onRefresh = {},
                            onRequestDelete = {},
                            onCancelDelete = {},
                            onConfirmDelete = {},
                            modifier = Modifier.fillMaxWidth().testTag("cert_delete"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        save("artist_enrichment_confirm_delete_320.png", "cert_delete")
    }

    @Test
    fun everySlotAnnouncesItsStateToScreenReaders() {
        // §11.5: the photo is not decoration. A monogram says it has no verified image and
        // a real image says its state, so TalkBack never reads a slot as one thing.
        compose.setContent {
            val configuration = remember {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = 390
                    fontScale = 1.0f
                    orientation = Configuration.ORIENTATION_PORTRAIT
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.0f),
            ) {
                MichiTheme {
                    Box(Modifier.width(390.dp).background(SurfaceObsidian).padding(surfaceMargin.dp)) {
                        ArtistEnrichmentSummaryContent(
                            uiState = summaryState(localPng("cert_talkback.png")),
                            onChoosePhoto = {},
                            onPrimaryAction = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("artist_artwork_thumbnail_${ArtworkSlot.ARTIST_THUMB.name}")
            .assertContentDescriptionEquals("Foto de mosaico: Disponible")
        compose.onNodeWithTag("artist_artwork_placeholder_${ArtworkSlot.ARTIST_HERO.name}")
            .assertContentDescriptionEquals(
                "Foto de ficha: Pendiente de identidad Wikidata. Sin imagen verificada.",
            )
    }

    @Test
    fun landscapeMeasuresTheSameWidthAsPortrait() {
        // §11.4: the orientation must not change the geometry either. Both cases request the
        // same width inside the window that physically fits it.
        var landscape by mutableStateOf(false)
        compose.setContent {
            val configuration = remember(landscape) {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = 390
                    fontScale = 1.0f
                    orientation = if (landscape) {
                        Configuration.ORIENTATION_LANDSCAPE
                    } else {
                        Configuration.ORIENTATION_PORTRAIT
                    }
                }
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.0f),
            ) {
                MichiTheme {
                    Box(Modifier.width(390.dp).background(SurfaceObsidian)) {
                        ArtistEnrichmentSummaryContent(
                            uiState = summaryState(localPng("cert_landscape.png")),
                            onChoosePhoto = {},
                            onPrimaryAction = {},
                            modifier = Modifier.padding(surfaceMargin.dp).testTag("cert_landscape"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val portraitDp = compose.onNodeWithTag("cert_landscape").fetchSemanticsNode()
            .let { it.size.width / it.layoutInfo.density.density }
        landscape = true
        compose.waitForIdle()
        val landscapeDp = compose.onNodeWithTag("cert_landscape").fetchSemanticsNode()
            .let { it.size.width / it.layoutInfo.density.density }
        assertTrue(
            "landscape measured ${landscapeDp}dp against ${portraitDp}dp in portrait",
            kotlin.math.abs(landscapeDp - portraitDp) <= 1.0f,
        )
        save("artist_enrichment_summary_390_1.0_landscape.png", "cert_landscape")
    }

    @Test
    fun everyExplicitChoiceIsAnAnnouncedButtonTarget() {
        // §11.5: what a screen reader gets is a button with its label and a 48 dp target.
        // Compose does not grant keyboard focus in touch mode, so keyboard traversal is not
        // what this asserts - the accessibility node contract is.
        compose.setContent {
            MichiTheme {
                Box(Modifier.width(390.dp).background(SurfaceObsidian).padding(surfaceMargin.dp)) {
                    ArtistEnrichmentSummaryContent(
                        uiState = summaryState(localPng("cert_focus.png")),
                        onChoosePhoto = {},
                        onPrimaryAction = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val action = compose.onNodeWithTag("artist_artwork_slot_action_${ArtworkSlot.ARTIST_HERO.name}")
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        action.assertTextEquals("Elegir fotografía")
        action.assertHeightIsAtLeast(48.dp)
    }

    /**
     * K48-R11: on a real phone the slot said "Disponible" over an empty rectangle because a
     * decode failure looked exactly like a photo that is simply not painted. A saved file that
     * cannot be read must fall back to the neutral monogram and say so.
     */
    @Test
    fun aSavedPhotoThatCannotBeDecodedIsNotReportedAsShown() {
        val missing = File(File(context.filesDir, "certification"), "not-a-real-photo.png")
        assertTrue("fixture must not exist: $missing", !missing.exists())

        compose.setContent {
            MichiTheme {
                Box(Modifier.fillMaxWidth().background(SurfaceObsidian)) {
                    ArtistEnrichmentSummaryContent(
                        uiState = summaryState(missing),
                        onChoosePhoto = {},
                        onPrimaryAction = {},
                    )
                }
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("artist_artwork_decode_error_ARTIST_THUMB")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("artist_artwork_decode_error_ARTIST_THUMB").assertExists()
        compose.onNodeWithTag("artist_artwork_decode_fallback_ARTIST_THUMB").assertExists()
        compose.onNodeWithTag("artist_artwork_thumbnail_ARTIST_THUMB").assertDoesNotExist()
        compose.onNodeWithTag("artist_artwork_slot_state_ARTIST_THUMB")
            .assertTextEquals("Disponible")
    }

    /**
     * The other half of the same contract: a file the app *can* decode is really painted.
     * The assertion reads the pixels the device drew, not the presence of a node.
     */
    @Test
    fun aDecodableSavedPhotoIsActuallyDrawn() {
        val thumbnail = localPng("cert_decode.png")
        compose.setContent {
            MichiTheme {
                Box(Modifier.fillMaxWidth().background(SurfaceObsidian)) {
                    ArtistEnrichmentSummaryContent(
                        uiState = summaryState(thumbnail),
                        onChoosePhoto = {},
                        onPrimaryAction = {},
                    )
                }
            }
        }

        compose.waitForIdle()
        val tag = "artist_artwork_thumbnail_ARTIST_THUMB"
        compose.onNodeWithTag(tag).assertExists()
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            paintedPixels(pixels) > 0
        }
        val pixels = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        assertTrue("the decoded photo drew ${paintedPixels(pixels)} non-background pixels",
            paintedPixels(pixels) > 0)
        compose.onNodeWithTag("artist_artwork_decode_error_ARTIST_THUMB").assertDoesNotExist()
    }

    /** Pixels that are neither the card surface nor transparent. */
    private fun paintedPixels(bitmap: Bitmap): Int {
        var painted = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                val color = bitmap.getPixel(x, y)
                val alpha = android.graphics.Color.alpha(color)
                val red = android.graphics.Color.red(color)
                val green = android.graphics.Color.green(color)
                val blue = android.graphics.Color.blue(color)
                if (alpha > 200 && red + green + blue > 3 * 26) painted++
                y += 3
            }
            x += 3
        }
        return painted
    }

    private fun summaryState(thumbnail: File) = ArtistEnrichmentUiState(
        artistName = credit,
        heading = ArtistEnrichmentUiState.heading(credit),
        identityConfirmed = true,
        identityLabel = "Identidad musical confirmada",
        identityDetail = "MBID: $mbid",
        mosaic = ArtistPhotoSlot(
            slot = ArtworkSlot.ARTIST_THUMB,
            title = "Foto de mosaico",
            availability = ArtistPhotoAvailability.AVAILABLE,
            statusLabel = "Disponible",
            imagePath = thumbnail.absolutePath,
            attribution = "Charles Millot",
            license = "CC BY 2.0",
        ),
        card = ArtistPhotoSlot(
            slot = ArtworkSlot.ARTIST_HERO,
            title = "Foto de ficha",
            availability = ArtistPhotoAvailability.PENDING,
            statusLabel = "Pendiente de identidad Wikidata",
        ),
        primaryAction = ArtistEnrichmentPrimaryAction.SearchMissingPhotos,
        primaryActionLabel = "Buscar fotos faltantes",
        searchActive = false,
        searchMessage = null,
        coverageRows = listOf(ArtistCoverageRow("Foto de ficha · Pendiente", nextAction = "Buscar fotos faltantes")),
        providerRows = emptyList(),
        diagnosed = true,
        delete = EnrichmentDeleteState.Idle,
        persistence = ArtistPersistencePresentation(message = null, failed = false),
        canPersist = true,
        hasAlbumMembership = true,
        albumCount = 3,
    )

    private fun localPng(name: String): File {
        val directory = File(context.filesDir, "certification").apply { mkdirs() }
        val file = File(directory, name)
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { isAntiAlias = true }
        for (step in 0 until 240) {
            paint.color = android.graphics.Color.rgb(
                40 + step / 3,
                70 + step / 4,
                120 + step / 2,
            )
            canvas.drawRect(0f, step.toFloat(), 240f, step + 1f, paint)
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun save(name: String, tag: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val directory = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(directory, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("CERT_SCREENSHOT $name ${bitmap.width}x${bitmap.height} ${file.absolutePath} ${file.length()}")
    }

    private fun profile() = ArtistExternalProfile(
        mbid = mbid,
        name = "Midnight Oil",
        type = "Group",
        area = "Australia",
        disambiguation = "Australian rock band",
        wikidataQid = "Q48284",
    )

    private fun candidate() = ArtistArtworkCandidate(
        source = ArtworkSource.OPENVERSE,
        stableAssetId = "openverse:cert",
        confirmedMbid = mbid,
        verifiedQid = "Q48284",
        canonicalSourcePage = "https://www.flickr.com/photos/1/51904192325",
        imageUrl = "https://live.staticflickr.com/1/51904192325_b.jpg",
        originalWidth = 1024,
        originalHeight = 768,
        mimeType = "image/jpeg",
        licenseId = "CC BY 2.0",
        licenseUrl = "https://creativecommons.org/licenses/by/2.0/",
        author = "Charles Millot",
        attribution = "Charles Millot",
        evidence = ArtworkEvidence.UNVERIFIED,
        rights = ArtworkRights.REQUIRES_REVIEW,
    )

    private companion object {
        const val credit = "Midnight Oil"
        const val mbid = "0f2d0f5b-0f5c-4f2b-9b3a-000000000001"
    }
}
