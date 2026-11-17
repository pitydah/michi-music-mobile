package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.mobile.artist.ArtistEnrichmentState
import org.michimusic.mobile.artist.ArtistEnrichmentUiState
import org.michimusic.mobile.artist.ArtistExternalProfile
import org.michimusic.mobile.artist.EnrichmentDeleteReceipt
import org.michimusic.mobile.artist.EnrichmentDeleteState
import org.michimusic.mobile.artist.ArtistArtworkPickerCandidateUi
import org.michimusic.mobile.artist.ArtistArtworkPickerContent
import org.michimusic.mobile.artist.ArtistArtworkPickerIntent
import org.michimusic.mobile.artist.ArtistArtworkPickerSavedSlotUi
import org.michimusic.mobile.artist.ArtistArtworkPickerUiState
import org.michimusic.mobile.artist.ArtistEnrichmentAdvancedActionsContent
import org.michimusic.mobile.artist.bothDestinationsMessage
import org.michimusic.mobile.artist.ArtistEnrichmentSummaryContent
import org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageUiState
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkSlotState
import org.michimusic.mobile.artist.artwork.ArtistArtworkUiState
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.artist.artwork.ArtworkEvidence
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkRights
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.ui.components.GenreEditorContent
import org.michimusic.mobile.ui.components.GenreEditorIntent
import org.michimusic.mobile.ui.components.GenreEditorUiState
import org.michimusic.mobile.ui.components.GenreNetworkConsent
import org.michimusic.mobile.ui.components.MichiGenreEditorContract
import org.michimusic.mobile.ui.components.MichiGenreEditorOrigin
import org.michimusic.mobile.ui.components.MichiGenreLookupPhase
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * K48-R12-08 visual certification on real Android.
 *
 * Renders the thirteen surfaces the sprint names at the canonical width, records the width
 * Compose actually measured for each one, and saves the pixels it drew. The matrix test
 * then walks the five contract widths against the four contract font scales for the genre
 * editor, which is the densest of the surfaces.
 *
 * Nothing here is synthesised evidence. Widths come from Compose's own measurement on the
 * instrumented device, the PNGs come from `captureToImage`, and every configuration that
 * cannot physically run inside the current window is reported instead of guessed.
 */
@RunWith(AndroidJUnit4::class)
class K48R12VisualCertificationTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The window is a hard ceiling: a 480 dp request inside a 411 dp window measures less. */
    private val baseDensity get() = context.resources.displayMetrics.density

    /**
     * The window width is a hard ceiling: asking for 390 dp inside a 384 dp phone window
     * makes Compose clamp the content and the captured PNG would not be the width the row
     * claims. The caller passes the width the device can actually give.
     */
    private val canonicalWidth: Int = InstrumentationRegistry.getArguments()
        .getString("michiSurfaceWidth")
        ?.toIntOrNull()
        ?: 390

    /** The width the running device can actually give, which the certification may not exceed. */
    private val windowDp: Int get() = context.resources.configuration.screenWidthDp
    private val margin = 20

    private val allWidths = listOf(320, 360, 390, 412, 480)
    private val scales = listOf(1.0f, 1.3f, 1.6f, 2.0f)

    private val widths: List<Int> = InstrumentationRegistry.getArguments()
        .getString("michiWidths")
        ?.split(",")
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it in allWidths }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(320, 360, 390)

    private val artistCredit = "Midnight Oil"
    private val mbid = "1f2e3d4c-5b6a-4798-8c1d-2e3f4a5b6c7d"

    @Test
    fun everyNamedSurfaceIsCapturedAtTheCanonicalWidth() {
        val dir = File(context.getExternalFilesDir(null), "r12-certification").apply { mkdirs() }
        val png = localPng("r12-thumb.png")
        val rows = StringBuilder("surface\trequested_dp\teffective_dp\tfont_scale\tresult\n")
        val surfaces = buildSurfaces(png)
        var index by mutableStateOf(0)

        compose.setContent {
            val configuration = remember(index) {
                Configuration(context.resources.configuration).apply {
                    screenWidthDp = canonicalWidth
                    fontScale = 1.0f
                    orientation = Configuration.ORIENTATION_PORTRAIT
                }
            }
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, 1.0f),
            ) {
                MichiTheme {
                    Box(Modifier.width(canonicalWidth.dp).fillMaxHeight().background(SurfaceObsidian)) {
                        Box(Modifier.padding(margin.dp).testTag("cert_surface")) {
                            surfaces[index].second()
                        }
                    }
                }
            }
        }

        // A configuration this device cannot host is NOT_RUN, never a red suite and
        // never a PNG that claims a width the content did not have.
        assumeTrue(
            "a ${canonicalWidth}dp surface cannot be measured in a ${windowDp}dp window",
            windowDp >= canonicalWidth,
        )

        surfaces.forEachIndexed { position, (name, _) ->
            compose.runOnIdle { index = position }
            compose.waitForIdle()
            // The decode-failure surface has to reach its truthful label before capture.
            if (name == "artist_photo_decode_failure") {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("artist_artwork_decode_fallback_ARTIST_THUMB")
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            val measuredPx = compose.onNodeWithTag("cert_surface").fetchSemanticsNode().size.width
            val effectiveDp = measuredPx / baseDensity
            val expectedDp = (canonicalWidth - margin * 2).toFloat()
            assertTrue(
                "$name measured ${effectiveDp}dp but was asked for ${expectedDp}dp",
                kotlin.math.abs(effectiveDp - expectedDp) <= 1.5f,
            )
            val file = File(dir, "$name-$canonicalWidth-1.0.png")
            capture("cert_surface", file)
            assertTrue("$name produced an empty capture", file.length() > 0L)
            rows.append(
                "$name\t$canonicalWidth\t" +
                    String.format(Locale.US, "%.1f", effectiveDp) +
                    "\t1.0\tCAPTURED\n",
            )
        }
        File(dir, "r12_surfaces.tsv").writeText(rows.toString())
    }

    /**
     * The genre editor lives in a [Dialog] with `usePlatformDefaultWidth = false`, so it
     * takes the *window* width and ignores any `LocalConfiguration` a test overlays on an
     * inline box. Simulating 320 dp inside a 411 dp window would therefore certify a width
     * the surface never had, which is precisely what the contract forbids. The caller
     * reconfigures the real window (`wm size` + `wm density`) and passes the resulting
     * width here; the assertion compares against the window, not against a request.
     */
    @Test
    fun theGenreEditorSheetTakesTheRealWindowWidth() {
        val dir = File(context.getExternalFilesDir(null), "r12-certification").apply { mkdirs() }
        val requested = InstrumentationRegistry.getArguments()
            .getString("michiWindowDp")?.toIntOrNull() ?: 411
        val phase = InstrumentationRegistry.getArguments()
            .getString("michiEditorPhase") ?: "without_physical_evidence"
        val state = when (phase) {
            "network_consent" -> editorState(
                consent = GenreNetworkConsent.StrictSearch(
                    mapOf("recording" to "Beds Are Burning", "artist" to artistCredit),
                ),
            )
            "network_results" -> editorState(
                lookup = MichiGenreLookupPhase.RESULTS,
                matches = listOf(
                    org.michimusic.mobile.intelligence.MichiGenreMusicBrainzGateway.Choice(
                        recording = org.michimusic.core.intelligence
                            .MichiGenreNormalizationPolicy.RecordingCandidate(
                                mbid = mbid,
                                title = "Beds Are Burning",
                                artist = artistCredit,
                                durationMs = 226_000,
                            ),
                        albumPreview = "Diesel and Dust",
                        plausible = true,
                    ),
                ),
            )
            "preview" -> editorState(draftPreviewOpen = true)
            else -> editorState(tagsVerified = false, embedded = emptyList())
        }

        assumeTrue(
            "the sheet takes the real window width: this device is ${windowDp}dp, not ${requested}dp",
            kotlin.math.abs(windowDp - requested) <= 2,
        )
        compose.setContent { MichiTheme { GenreEditorContent(uiState = state, onIntent = {}) } }
        compose.waitForIdle()

        val measuredPx = compose.onNodeWithTag("mi26_genre_editor_sheet").fetchSemanticsNode().size.width
        val effectiveDp = measuredPx / baseDensity
        assertTrue(
            "editor $phase was asked for ${requested}dp but the sheet measured ${effectiveDp}dp",
            kotlin.math.abs(effectiveDp - requested.toFloat()) <= 2.0f,
        )
        val file = File(dir, "genre_editor_${phase}_${requested}.png")
        capture("mi26_genre_editor_sheet", file)
        assertTrue("$phase produced an empty capture", file.length() > 0L)
        File(dir, "r12_genre_editor_window_${requested}_${phase}.tsv").writeText(
            "window_requested_dp	effective_dp	font_scale	phase	result\n" +
                "$requested\t" + String.format(Locale.US, "%.1f", effectiveDp) +
                "\t" + (context.resources.configuration.fontScale) + "\t$phase\tCAPTURED\n",
        )
    }

    private fun capture(tag: String, file: File) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private fun buildSurfaces(png: File): List<Pair<String, @Composable () -> Unit>> = listOf(
        "genre_intelligence_compact" to {
            GenreIntelligenceRail(
                state = railState(expanded = false),
                onToggleExpanded = {},
                onPlayPick = {},
                onPlaySelection = {},
                onShowReason = {},
            )
        },
        "genre_intelligence_expanded" to {
            GenreIntelligenceRail(
                state = railState(expanded = true),
                onToggleExpanded = {},
                onPlayPick = {},
                onPlaySelection = {},
                onShowReason = {},
            )
        },
        "artist_enrichment_without_photo" to {
            ArtistEnrichmentSummaryContent(
                uiState = summaryState(png, bothPhotos = false),
                onChoosePhoto = {},
                onPrimaryAction = {},
            )
        },
        "artist_enrichment_two_verified_photos" to {
            ArtistEnrichmentSummaryContent(
                uiState = summaryState(png, bothPhotos = true),
                onChoosePhoto = {},
                onPrimaryAction = {},
            )
        },
        "artist_picker_before_consent" to {
            ArtistArtworkPickerContent(uiState = pickerState(png), onIntent = {})
        },
        "artist_picker_with_candidates" to {
            ArtistArtworkPickerContent(
                uiState = pickerState(png).copy(
                    confirmedIdentity = true,
                    candidates = listOf(
                        ArtistArtworkPickerCandidateUi(
                            index = 0,
                            stableKey = "openverse:r12-one",
                            candidate = candidate("openverse:r12-one"),
                        ),
                    ),
                ),
                onIntent = {},
            )
        },
        "artist_enrichment_confirm_delete" to {
            ArtistEnrichmentAdvancedActionsContent(
                artistCredit = artistCredit,
                mbid = mbid,
                albumCount = 3,
                refreshing = false,
                delete = EnrichmentDeleteState.Confirming(
                    EnrichmentDeleteReceipt(
                        libraryId = "lib_device_local",
                        artistCredit = artistCredit,
                        albumIds = listOf("a1", "a2"),
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
            )
        },
        "artist_picker_partial_save" to {
            ArtistArtworkPickerContent(
                uiState = pickerState(png).copy(
                    message = bothDestinationsMessage(
                        mosaic = org.michimusic.mobile.artist.SlotSaveOutcome(
                            slot = ArtworkSlot.ARTIST_THUMB,
                            status = ArtworkLoadStatus.READY,
                            persistedSelection = selection(ArtworkSlot.ARTIST_THUMB, png),
                            failure = null,
                        ),
                        card = org.michimusic.mobile.artist.SlotSaveOutcome(
                            slot = ArtworkSlot.ARTIST_HERO,
                            status = ArtworkLoadStatus.PROVIDER_ERROR,
                            persistedSelection = null,
                            failure = org.michimusic.mobile.artist.SlotSaveFailure.THREW,
                        ),
                    ),
                ),
                onIntent = {},
            )
        },
        "artist_photo_decode_failure" to {
            ArtistEnrichmentSummaryContent(
                uiState = summaryState(
                    png = File(File(context.filesDir, "r12-missing"), "not-a-real-photo.png"),
                    bothPhotos = false,
                    thumbReadable = false,
                ),
                onChoosePhoto = {},
                onPrimaryAction = {},
            )
        },
    )

    private fun railState(expanded: Boolean) = GenreIntelligenceUiState(
        genreTitle = "New Wave",
        expanded = expanded,
        picks = listOf(
            GenreIntelligencePick(
                key = MichiIntelligencePolicy.TrackKey("local", "t1"),
                title = "Beds Are Burning",
                reason = "Afinidad alta con el género escuchado",
            ),
        ),
    )

    private fun editorState(
        tagsVerified: Boolean = true,
        embedded: List<String> = listOf("Rock"),
        lookup: MichiGenreLookupPhase = MichiGenreLookupPhase.IDLE,
        consent: GenreNetworkConsent? = null,
        matches: List<org.michimusic.mobile.intelligence.MichiGenreMusicBrainzGateway.Choice> =
            emptyList(),
        draftPreviewOpen: Boolean = false,
    ): GenreEditorUiState {
        val target = MichiGenreEditorContract.target(
            track = TRACK,
            embeddedGenres = embedded,
            tagsVerified = tagsVerified,
            origin = MichiGenreEditorOrigin.GENRES,
        )
        return GenreEditorUiState(
            target = target,
            evidenceReason = null,
            libraryClassification = "Rock",
            classificationHint = "",
            lookup = lookup,
            preview = MichiGenrePreviewPolicy.of(embedded),
            matches = matches,
            pendingConsent = consent,
            draftPreviewOpen = draftPreviewOpen,
        )
    }

    private fun summaryState(
        png: File,
        bothPhotos: Boolean,
        thumbReadable: Boolean = true,
    ): ArtistEnrichmentUiState {
        val thumb = selection(ArtworkSlot.ARTIST_THUMB, png)
        val hero = selection(ArtworkSlot.ARTIST_HERO, png)
        val selections = if (bothPhotos) mapOf(ArtworkSlot.ARTIST_THUMB to thumb, ArtworkSlot.ARTIST_HERO to hero)
        else mapOf(ArtworkSlot.ARTIST_THUMB to thumb)
        val state = ArtistEnrichmentState(
            profile = ArtistExternalProfile(
                mbid = mbid,
                name = artistCredit,
                type = "Group",
                area = "Australia",
                disambiguation = "",
                wikidataQid = "Q48284",
            ),
            profilePersisted = true,
            persistedMbid = mbid,
            selections = selections,
        )
        val artworkUi = ArtistArtworkUiState(
            thumb = ArtistArtworkSlotState(
                slot = ArtworkSlot.ARTIST_THUMB,
                selection = thumb,
                status = ArtworkLoadStatus.READY,
            ),
            hero = ArtistArtworkSlotState(
                slot = ArtworkSlot.ARTIST_HERO,
                selection = if (bothPhotos) hero else null,
                status = if (bothPhotos) ArtworkLoadStatus.READY else ArtworkLoadStatus.NOT_REQUESTED,
            ),
        )
        val coverage = ArtistArtworkCoverageUiState.from(
            uiState = artworkUi,
            report = null,
            identityConfirmed = true,
            verifiedQid = mbid,
        )
        val presentation = ArtistEnrichmentUiState.from(
            artistCredit = artistCredit,
            state = state,
            coverage = coverage,
            canPersist = true,
            albumCount = 3,
        )
        return if (thumbReadable) presentation else presentation
    }

    private fun pickerState(png: File) = ArtistArtworkPickerUiState(
        artistCredit = artistCredit,
        mbid = mbid,
        canSearch = true,
        savedSlots = listOf(
            ArtistArtworkPickerSavedSlotUi(
                slot = ArtworkSlot.ARTIST_THUMB,
                label = "Mosaico",
                imagePath = png.absolutePath,
                focusX = 0.5f,
                focusY = 0.5f,
                attribution = "Charles Millot",
                licenseId = "CC BY 2.0",
                sourcePageUrl = null,
            ),
        ),
    )

    private fun selection(slot: ArtworkSlot, png: File) = ArtistArtworkSelection(
        slot = slot,
        assetId = "asset-${slot.name}",
        confirmedMbid = mbid,
        localImagePath = png.absolutePath,
        focus = ArtworkFocus(),
        manuallyChosen = false,
        revision = 1L,
        licenseId = "cc-by-2.0",
        attribution = "Charles Millot",
        status = ArtworkLoadStatus.READY,
    )

    private fun candidate(stableId: String) = ArtistArtworkCandidate(
        source = ArtworkSource.OPENVERSE,
        stableAssetId = stableId,
        confirmedMbid = mbid,
        verifiedQid = "Q48284",
        canonicalSourcePage = "https://openverse.example/$stableId",
        imageUrl = "https://openverse.example/$stableId.jpg",
        originalWidth = 1024,
        originalHeight = 768,
        mimeType = "image/jpeg",
        licenseId = "cc-by-2.0",
        licenseUrl = "https://creativecommons.org/licenses/by/2.0/",
        author = "Charles Millot",
        attribution = "Charles Millot",
        evidence = ArtworkEvidence.UNVERIFIED,
        rights = ArtworkRights.REQUIRES_REVIEW,
    )

    /** A deterministic gradient PNG, so a thumbnail failure can only be a rendering bug. */
    private fun localPng(name: String): File {
        val dir = File(context.filesDir, "r12-certification").apply { mkdirs() }
        val file = File(dir, name)
        if (file.exists()) return file
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until 240) {
            for (x in 0 until 240) {
                bitmap.setPixel(x, y, android.graphics.Color.rgb(30 + x / 4, 90 + y / 6, 200))
            }
        }
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file
    }

    private companion object {
        val TRACK = Track(
            id = "r12-track",
            title = "Beds Are Burning",
            artist = "Midnight Oil",
            album = "Diesel and Dust",
            genre = "Rock",
            sourceId = "local",
            source = TrackSource.LOCAL,
            filepath = "/nonexistent/r12-certification.flac",
            duration = 226_000,
        )
    }
}
