package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.artist.ArtistVariantHint
import org.michimusic.mobile.artist.ArtistVariantReason
import org.michimusic.mobile.artist.displayLabel
import org.michimusic.mobile.ui.theme.SurfaceContainer
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.TertiaryCyan

/**
 * K48-S5 — possible spelling variants are shown, never silently merged. Each row states
 * why the credits were kept apart and offers the identity review that could later
 * justify a shared presentation.
 */
@Composable
internal fun ArtistVariantNotice(
    hints: List<ArtistVariantHint>,
    onReviewIdentity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hints.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("artist_variant_notice"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Posibles variantes del mismo artista",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        hints.forEachIndexed { index, hint ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(hint.displayLabel(), color = TertiaryCyan, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(variantExplanation(hint.reason), color = TextSecondary, fontSize = 12.sp)
                }
                TextButton(
                    onClick = { onReviewIdentity(hint.credits.first()) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("artist_variant_review_$index"),
                ) { Text("Revisar identidad", color = TertiaryCyan) }
            }
        }
    }
}

internal fun variantExplanation(reason: ArtistVariantReason): String = when (reason) {
    ArtistVariantReason.IDENTITY_UNVERIFIED ->
        "No se han unificado porque falta verificar su identidad."
    ArtistVariantReason.MIXED_EVIDENCE ->
        "Solo una de las variantes tiene identidad verificada."
    ArtistVariantReason.IDENTITY_CONFLICT ->
        "Tienen identidades de MusicBrainz distintas."
}
