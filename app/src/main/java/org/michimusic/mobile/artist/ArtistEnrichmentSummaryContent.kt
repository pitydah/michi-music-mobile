package org.michimusic.mobile.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K48-R07: the enrichment summary as independent, renderable pieces.
 *
 * Every composable here takes presentation data and lambdas. None of them resolves a
 * service, opens the database, starts a request or reads a coroutine scope, so the
 * summary can be rendered in a test with no Koin, Room, Media3 or network.
 *
 * The local thumbnail path is an already-verified file inside the app cache. Coil
 * decodes it through the same disk cache the rest of the app uses; nothing here
 * downloads, and a slot without verified bytes renders a neutral monogram instead of
 * something that could pass for a recovered photograph.
 */

/** Miniatura lateral mínima del contrato §9.9 (320 dp, columna única). */
private val SlotThumbnailSize = 72.dp

@Composable
fun ArtistSummaryHeader(
    uiState: ArtistEnrichmentUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            // K48-S7: a credit like "'Til Tuesday" already starts with an
            // apostrophe; wrapping it again printed doubled quotes.
            uiState.heading,
            color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("artist_artwork_summary"),
        )
        Text(
            uiState.identityLabel,
            color = if (uiState.identityConfirmed) TertiaryCyan else TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.testTag("artist_identity_state"),
        )
        uiState.missingSummary?.let { missing ->
            Text(missing, color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.testTag("artist_artwork_missing_summary"))
        }
    }
}

/**
 * One photograph slot: the image when it really exists, a neutral monogram when it
 * does not, and the single word that says which of the five situations applies.
 */
@Composable
fun ArtworkSlotCard(
    slot: ArtistPhotoSlot,
    artistName: String,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PureWhite.copy(alpha = 0.04f))
            .padding(12.dp)
            .testTag("artist_artwork_slot_${slot.slot.name}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            slot.title.uppercase(),
            color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtistSlotThumbnail(slot = slot, artistName = artistName)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    slot.statusLabel,
                    color = if (slot.isSatisfied) TertiaryCyan else PureWhite,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("artist_artwork_slot_state_${slot.slot.name}"),
                )
                slot.detail?.let { reason ->
                    Text(
                        reason,
                        color = TextSecondary, fontSize = 11.sp, maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Provenance of a real image only; it never substitutes the image.
                if (slot.hasThumbnail) {
                    val provenance = listOfNotNull(slot.attribution, slot.license)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (provenance.isNotBlank()) {
                        Text(provenance, color = TextSecondary, fontSize = 10.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!slot.isSatisfied) {
                    TextButton(
                        onClick = onChoose,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("artist_artwork_slot_action_${slot.slot.name}"),
                    ) {
                        Text("Elegir fotografía", color = SecondaryPurple, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * The thumbnail itself. A verified local file is decoded through Coil; anything else
 * is a monogram on a neutral surface with no photographic pretense.
 */
@Composable
private fun ArtistSlotThumbnail(
    slot: ArtistPhotoSlot,
    artistName: String,
) {
    val shape = RoundedCornerShape(12.dp)
    val describe = if (slot.hasThumbnail) {
        "${slot.title}: ${slot.statusLabel}"
    } else {
        "${slot.title}: ${slot.statusLabel}. Sin imagen verificada."
    }
    if (slot.hasThumbnail) {
        AsyncImage(
            model = File(requireNotNull(slot.imagePath)),
            contentDescription = describe,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(SlotThumbnailSize)
                .clip(shape)
                .testTag("artist_artwork_thumbnail_${slot.slot.name}"),
        )
    } else {
        Box(
            modifier = Modifier
                .size(SlotThumbnailSize)
                .clip(shape)
                .background(PureWhite.copy(alpha = 0.08f))
                .semantics { contentDescription = describe }
                .testTag("artist_artwork_placeholder_${slot.slot.name}"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                artistName.trim().take(1).ifBlank { "?" }.uppercase(),
                color = TextSecondary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The one action the summary offers, labelled by the action itself (§9.6, R03). */
@Composable
fun ArtistPrimaryAction(
    action: ArtistEnrichmentPrimaryAction,
    label: String,
    busy: Boolean,
    onAction: (ArtistEnrichmentPrimaryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { onAction(action) },
        enabled = action != ArtistEnrichmentPrimaryAction.None && !busy,
        colors = ButtonDefaults.buttonColors(
            containerColor = PrimaryPinkContainer,
            contentColor = SurfaceObsidian,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("artist_artwork_primary_action"),
    ) {
        Text(label)
    }
}

/** One collapsible region; the label states what is inside, not that it is collapsed. */
@Composable
fun ArtistExpandableSectionToggle(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
    ) {
        Text(title, color = PureWhite, fontSize = 12.sp)
    }
}

/**
 * Causal diagnosis: each slot and each provider reports its own outcome so a missing
 * photograph is never flattened into one generic sentence.
 */
@Composable
fun ArtistCoverageDiagnosticContent(
    rows: List<ArtistCoverageRow>,
    providers: List<ArtistCoverageRow>,
    diagnosed: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(row.title, color = PureWhite, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                row.detail?.let { Text(it, color = TextSecondary, fontSize = 11.sp) }
                row.nextAction?.let {
                    Text("Siguiente paso: $it", color = SecondaryPurple, fontSize = 11.sp)
                }
            }
        }
        if (!diagnosed) {
            Text(
                "Aún no diagnosticado. Abre la ficha para obtener un recibo por proveedor.",
                color = TextSecondary, fontSize = 11.sp,
            )
        }
        providers.forEach { provider ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(provider.title, color = PureWhite, fontSize = 11.sp)
                provider.detail?.let { Text(it, color = TextSecondary, fontSize = 11.sp) }
            }
        }
        Text(
            "Solo se muestran estados causales: nunca rutas, URLs ni datos privados.",
            color = TextSecondary, fontSize = 11.sp,
        )
    }
}

/**
 * §9.9 responsive rule: two horizontal slot cards only when each keeps at least
 * [SlotThumbnailSize] of image plus room for its state and action at the current font
 * scale. At 320 dp the minimum width cannot hold two columns, so the single column
 * wins; at 360–390 dp two columns are allowed only with normal text; at 412–480 dp
 * they are allowed unless the font scale is large.
 */
@Composable
fun artworkSlotsInline(): Boolean {
    val configuration = LocalConfiguration.current
    return artworkSlotsInline(configuration.screenWidthDp, configuration.fontScale)
}

/**
 * K48-R07 §9.9: the same decision as pure data so the responsive contract can be tested
 * without a device. Each column needs the thumbnail plus the text block carrying state and
 * CTA; a large font scale widens and heightens that block, so two columns are refused there
 * instead of clipping the label.
 */
internal fun artworkSlotsInline(widthDp: Int, fontScale: Float): Boolean {
    // §9.9: two columns are required from 412 dp and allowed from 390 dp; below that the
    // thumbnail plus its state and CTA would be clipped, so the single column wins.
    val minimumColumn = SlotThumbnailSize + 112.dp
    return fontScale < 1.6f && widthDp.dp >= minimumColumn * 2
}

/** Divider used between stacked collapsible regions. */
@Composable
fun ArtistSectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), modifier = modifier)
}
