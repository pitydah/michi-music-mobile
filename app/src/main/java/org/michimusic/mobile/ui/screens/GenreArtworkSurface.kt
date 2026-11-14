package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * The one painted surface shared by both views.
 *
 * Invariants enforced here (and nowhere duplicated):
 * - the bitmap is fitted with one uniform scale; the clip describes the *painted*
 *   rect, so a residual band can never look like a second, misaligned frame;
 * - the asset paints whenever it is available — [Ui7VisualQualityFlags] are
 *   reported, never used as a veto;
 * - exactly one click target and one accessible name per card;
 * - the numeric chip is anchored to the real artwork border, 12dp inside;
 * - no Compose title, no second border, no mount/footer and no technical text.
 */
@Composable
internal fun GenreArtworkSurface(
    spec: Ui7VisualAssetSpec,
    genre: LibraryGenre,
    count: Int,
    view: String,
    testTag: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    artwork: @Composable (Modifier) -> Unit,
) {
    val chip = rememberGenreCounterSize(count)
    var focused by remember { mutableStateOf(false) }
    val label = remember(genre.id, count) { genreCardLabel(genre) }
    BoxWithConstraints(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onOpen)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag(testTag),
    ) {
        val cellWidthDp = maxWidth.value
        val cellHeightDp = maxHeight.value
        val decision = remember(spec, cellWidthDp, cellHeightDp, chip) {
            ui7ArtworkDecision(spec, cellWidthDp, cellHeightDp, chip.widthDp, chip.heightDp)
        }
        val frame = remember(spec, cellWidthDp, cellHeightDp) {
            ui7ArtworkFrame(spec, cellWidthDp, cellHeightDp)
        }
        LaunchedEffect(spec.fileName, decision) {
            GenreArtworkDiagnostics.assetResolved(view, spec.fileName)
            GenreArtworkDiagnostics.quality(view, spec.fileName, decision.quality)
        }
        // Continuous surface behind the art: no divider, no footer, no band surface.
        Box(Modifier.fillMaxSize().background(SurfaceObsidian))
        if (frame != null) {
            val showChip = counterFitsInsideArtwork(
                frame.widthDp, frame.heightDp, chip.widthDp, chip.heightDp,
            )
            if (!showChip) {
                LaunchedEffect(spec.fileName) {
                    GenreArtworkDiagnostics.counterPending(view, spec.fileName)
                }
            }
            Box(
                Modifier
                    .offset(frame.rect.left.dp, frame.rect.top.dp)
                    .size(frame.widthDp.dp, frame.heightDp.dp)
                    .clip(frame.shape)
                    .testTag("genre_art_frame_${genre.id}"),
            ) {
                artwork(Modifier.fillMaxSize())
                if (showChip) {
                    GenreCounterChip(
                        count = count,
                        testTag = "genre_counter_${genre.id}",
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = GENRE_COUNTER_INSET_DP.dp,
                                bottom = GENRE_COUNTER_INSET_DP.dp,
                            ),
                    )
                }
                if (focused) {
                    // Transient interaction affordance, not a second decorative frame.
                    Box(
                        Modifier.fillMaxSize().border(
                            width = 2.dp,
                            color = PureWhite.copy(alpha = 0.92f),
                            shape = frame.shape,
                        ),
                    )
                }
            }
        } else {
            LaunchedEffect(spec.fileName) {
                GenreArtworkDiagnostics.counterPending(view, spec.fileName)
            }
        }
    }
}

/** Neutral fill for the residual area around a fitted artwork. */
@Composable
internal fun GenreArtworkBackdrop(modifier: Modifier = Modifier, color: Color = SurfaceObsidian) {
    Box(modifier.fillMaxSize().background(color))
}
