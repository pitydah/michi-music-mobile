package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/** Real scoped track count; the singular/plural form stays Spanish. */
internal fun genreCounterLabel(count: Int): String =
    "$count ${if (count == 1) "pista" else "pistas"}"

internal data class GenreCounterSize(val widthDp: Float, val heightDp: Float)

/**
 * Upper bound of a one-line chip at the active font scale.
 *
 * Layout uses it to decide whether the chip fits the documented safe region
 * inside the artwork, or has to use the conservative continuous-surface
 * placement. Measuring the widest realistic label once per grid avoids a text
 * measurer (and a layout pass) per card.
 */
@Composable
internal fun rememberGenreCounterMaxSize(): GenreCounterSize {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val fontScale = density.fontScale
    return remember(density, fontScale) {
        val measured = measurer.measure("999 pistas", MichiTypography.metadata)
        with(density) {
            GenreCounterSize(
                widthDp = measured.size.width.toDp().value +
                    2 * GENRE_COUNTER_HORIZONTAL_PADDING_DP,
                heightDp = measured.size.height.toDp().value +
                    2 * GENRE_COUNTER_VERTICAL_PADDING_DP,
            )
        }
    }
}

/**
 * The one count affordance of both views: metadata type, a single line, 8dp/4dp
 * padding, 7dp radius and a translucent obsidian backing so it stays legible on
 * any artwork without covering it with a full-width gradient or a footer band.
 * Callers own placement (12dp from the optical border) and the test tag.
 */
@Composable
internal fun GenreCounterChip(
    count: Int,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Text(
        text = genreCounterLabel(count),
        style = MichiTypography.metadata,
        color = TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clip(RoundedCornerShape(GENRE_COUNTER_RADIUS_DP.dp))
            .background(SurfaceObsidian.copy(alpha = 0.88f))
            .padding(
                horizontal = GENRE_COUNTER_HORIZONTAL_PADDING_DP.dp,
                vertical = GENRE_COUNTER_VERTICAL_PADDING_DP.dp,
            ),
    )
}
