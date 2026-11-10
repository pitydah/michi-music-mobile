package org.michimusic.mobile.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/** One navigation contract across expanded and compact layout, with a 48dp touch target. */
@Composable
internal fun DeckNavigationButton(
    previous: Boolean,
    compact: Boolean,
    circularArtwork: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = if (circularArtwork) {
        if (previous) "Artista anterior" else "Artista siguiente"
    } else {
        if (previous) "Álbum anterior" else "Álbum siguiente"
    }
    val tag = if (previous) "michi_card_previous" else "michi_card_next"
    val icon = if (previous) Icons.AutoMirrored.Filled.ArrowBack
        else Icons.AutoMirrored.Filled.ArrowForward
    if (compact) {
        // Same no-disc, 48dp Michi icon treatment used in the rest of the player.
        MichiIconAction(
            icon = icon, contentDescription = label, onClick = onClick,
            enabled = enabled, minTargetSize = 48.dp, iconSize = 22.dp,
            testTag = tag, tint = PureWhite,
        )
    } else {
        AeroActionButton(
            label = if (previous) "Anterior" else "Siguiente",
            accessibilityLabel = label,
            icon = icon, onClick = onClick, enabled = enabled,
            kind = AeroActionKind.Quiet, testTag = tag,
        )
    }
}

/**
 * Navigation remains reachable when the artwork stage does not fit. The three 48dp
 * touch targets scroll horizontally instead of overflowing a sub-144dp viewport.
 * Place the open action first so it stays visible in narrow windows; previous/next
 * are available by scrolling and through accessibility focus.
 */
@Composable
internal fun CompactDeckFallback(
    position: Int,
    count: Int,
    label: String,
    circularArtwork: Boolean,
    enabled: Boolean,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val scrollState = rememberScrollState()
        val availableHeight = maxHeight
        Column(
            modifier = Modifier.fillMaxSize().padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (availableHeight >= 96.dp) {
                Text(
                    text = "$label · $position/$count",
                    style = MichiTypography.microLabel,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag("michi_card_compact_hint"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)
                    .testTag("michi_card_compact_actions"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                onOpen?.let { open ->
                    MichiIconAction(
                        icon = Icons.Default.OpenInNew,
                        contentDescription = if (circularArtwork) "Abrir artista seleccionado"
                            else "Abrir álbum seleccionado",
                        onClick = open, minTargetSize = 48.dp, iconSize = 22.dp,
                        testTag = "michi_card_compact_open", tint = PureWhite,
                    )
                }
                onPrevious?.let { previous ->
                    DeckNavigationButton(
                        previous = true, compact = true, circularArtwork = circularArtwork,
                        enabled = enabled, onClick = previous,
                    )
                }
                onNext?.let { next ->
                    DeckNavigationButton(
                        previous = false, compact = true, circularArtwork = circularArtwork,
                        enabled = enabled, onClick = next,
                    )
                }
            }
        }
    }
}
