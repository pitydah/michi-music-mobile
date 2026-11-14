package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.PureWhite

/** Full Spanish phrase for TalkBack/detail; it is never painted inside the card. */
internal fun genreCounterLabel(count: Int): String =
    "$count ${if (count == 1) "pista" else "pistas"}"

internal data class GenreCounterSize(val widthDp: Float, val heightDp: Float)

/** Numeric chip typography: white 14sp SemiBold, tabular figures where available. */
internal val GenreCounterTextStyle = TextStyle(
    fontSize = 14.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.SemiBold,
    color = PureWhite,
    fontFeatureSettings = "tnum",
)

internal const val GENRE_COUNTER_BASE_SIZE_DP = 28
internal const val GENRE_COUNTER_HORIZONTAL_PADDING_DP = 6
internal const val GENRE_COUNTER_VERTICAL_PADDING_DP = 4
internal const val GENRE_COUNTER_INSET_DP = 12

/** Neutral translucent chip fill from the contract; never a saturated status badge. */
internal val GenreCounterFill = Color(0xFFB8BEC8).copy(alpha = 0.45f)

/**
 * Measured chip box for the genre's real count at the current font scale. The
 * renderer uses this exact size for the safe-region check; the widest possible
 * four-digit chip is never substituted for a one-digit genre.
 */
@Composable
internal fun rememberGenreCounterSize(count: Int): GenreCounterSize {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val fontScale = density.fontScale
    val text = count.toString()
    return remember(density, fontScale, text) {
        val measured = measurer.measure(text, GenreCounterTextStyle)
        with(density) {
            GenreCounterSize(
                widthDp = maxOf(
                    GENRE_COUNTER_BASE_SIZE_DP.toFloat(),
                    measured.size.width.toDp().value + 2 * GENRE_COUNTER_HORIZONTAL_PADDING_DP,
                ),
                heightDp = maxOf(
                    GENRE_COUNTER_BASE_SIZE_DP.toFloat(),
                    measured.size.height.toDp().value + 2 * GENRE_COUNTER_VERTICAL_PADDING_DP,
                ),
            )
        }
    }
}

/**
 * The one count affordance of both views: only the digits are visible, 14sp white
 * on the neutral 45% chip, bottom-end of the artwork. The chip is a capsule whose
 * radius is half its height and becomes circular for one/two digits; it grows from
 * the measured text instead of ellipsizing. No stroke, shadow, icon, "pistas" or
 * separate click target.
 */
@Composable
internal fun GenreCounterChip(
    count: Int,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Box(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .heightIn(min = GENRE_COUNTER_BASE_SIZE_DP.dp)
            .widthIn(min = GENRE_COUNTER_BASE_SIZE_DP.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(GenreCounterFill)
            .padding(
                horizontal = GENRE_COUNTER_HORIZONTAL_PADDING_DP.dp,
                vertical = GENRE_COUNTER_VERTICAL_PADDING_DP.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = GenreCounterTextStyle,
            maxLines = 1,
        )
    }
}
