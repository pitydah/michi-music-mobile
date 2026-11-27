package org.michimusic.mobile.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
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

/**
 * K48-R07 §9.4: the whole summary as one renderable unit. It answers the three questions in
 * reading order - is there a mosaic photo, is there a card photo, what completes what is
 * missing - and delegates every decision to its caller, so no state lives here.
 */
@Composable
fun ArtistEnrichmentSummaryContent(
    uiState: ArtistEnrichmentUiState,
    onChoosePhoto: () -> Unit,
    onPrimaryAction: (ArtistEnrichmentPrimaryAction) -> Unit,
    modifier: Modifier = Modifier,
    onChooseSlot: (org.michimusic.mobile.artist.artwork.ArtworkSlot) -> Unit = { onChoosePhoto() },
    restoreFocus: Boolean = false,
    restoreFocusSlot: org.michimusic.mobile.artist.artwork.ArtworkSlot? = null,
    onFocusRestored: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val slotToRestore = if (uiState.identityConfirmed && uiState.canPersist)
            restoreFocusSlot ?: uiState.mosaic.slot else null
        fun choose(slot: ArtistPhotoSlot) {
            if (uiState.isBusy) return
            if (slot.recovery?.route == ArtistS3RecoveryRoute.REVIEW_IDENTITY)
                onPrimaryAction(ArtistEnrichmentPrimaryAction.ReviewIdentity)
            else onChooseSlot(slot.slot)
        }
        ArtistSummaryHeader(uiState, modifier = Modifier.artistS3ReturnFocus(
            restoreFocus && slotToRestore == null, onFocusRestored))
        ArtistS3OverviewStatus(uiState)
        if (!uiState.identityConfirmed) {
            // A search or a photograph cannot establish an artist's identity.
            // The only next task is an explicit MusicBrainz identity review.
            Text(
                "Confirma primero a qué artista pertenece tu música. No cambiaremos las etiquetas.",
                color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.testTag("artist_identity_first_explanation"),
            )
            ArtistPrimaryAction(
                action = ArtistEnrichmentPrimaryAction.ReviewIdentity,
                label = "Identificar artista",
                busy = uiState.isBusy,
                onAction = onPrimaryAction,
            )
        } else if (!uiState.canPersist) {
            Text(
                "Para revisar y guardar fotografías, selecciona una biblioteca individual con pertenencias verificadas.",
                color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.testTag("artist_s3_scope_read_only"),
            )
        } else {
            Text("Información · ${if (uiState.biographyAvailable) "Biografía disponible" else "Biografía pendiente"}",
                color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.testTag("artist_enrichment_information_status"))
            Text("FOTOGRAFÍAS · MOSAICO Y FICHA", color = TertiaryCyan,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            ArtistS3SlotExplanation(org.michimusic.mobile.artist.artwork.ArtworkSlot.ARTIST_THUMB)
            ArtistS3SlotExplanation(org.michimusic.mobile.artist.artwork.ArtworkSlot.ARTIST_HERO)
            // The two destination-specific actions already open the picker. Do not duplicate
            // them with another button that performs the same navigation.
            if (uiState.primaryAction != ArtistEnrichmentPrimaryAction.None &&
                uiState.primaryAction.panelEffect() != ArtistEnrichmentPanelEffect.OpenPicker) {
                ArtistPrimaryAction(
                    action = uiState.primaryAction, label = uiState.primaryActionLabel,
                    busy = uiState.isBusy, onAction = onPrimaryAction,
                )
            }
            if (artworkSlotsInline()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ArtworkSlotCard(
                        slot = uiState.mosaic,
                        artistName = uiState.artistName,
                        onChoose = { choose(uiState.mosaic) },
                        actionsEnabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f).artistS3ReturnFocus(
                            restoreFocus && slotToRestore == uiState.mosaic.slot, onFocusRestored),
                        compact = true,
                    )
                    ArtworkSlotCard(
                        slot = uiState.card,
                        artistName = uiState.artistName,
                        onChoose = { choose(uiState.card) },
                        actionsEnabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f).artistS3ReturnFocus(
                            restoreFocus && slotToRestore == uiState.card.slot, onFocusRestored),
                        compact = true,
                    )
                }
            } else {
                ArtworkSlotCard(
                    slot = uiState.mosaic,
                    artistName = uiState.artistName,
                    onChoose = { choose(uiState.mosaic) },
                    actionsEnabled = !uiState.isBusy,
                    modifier = Modifier.artistS3ReturnFocus(
                        restoreFocus && slotToRestore == uiState.mosaic.slot, onFocusRestored),
                )
                ArtworkSlotCard(
                    slot = uiState.card,
                    artistName = uiState.artistName,
                    onChoose = { choose(uiState.card) },
                    actionsEnabled = !uiState.isBusy,
                    modifier = Modifier.artistS3ReturnFocus(
                        restoreFocus && slotToRestore == uiState.card.slot, onFocusRestored),
                )
            }
            // Existing manually selected photos remain changeable, but only via the
            // preview and replacement guard in the picker, never as an automatic write.
            if (uiState.mosaic.isSatisfied || uiState.card.isSatisfied) {
                TextButton(
                    onClick = onChoosePhoto,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("artist_s3_manage_saved_photos"),
                ) { Text("Revisar o cambiar fotografías guardadas", color = TertiaryCyan) }
            }
        }
    }
}

@Composable
fun ArtistSummaryHeader(
    uiState: ArtistEnrichmentUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Enriquecer · ${uiState.artistName}",
            color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("artist_artwork_summary"),
        )
        Text(
            uiState.identityLabel,
            color = if (uiState.identityConfirmed) TertiaryCyan else TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.testTag("artist_identity_state"),
        )
        if (uiState.identityConfirmed) uiState.missingSummary?.let { missing ->
            Text(missing, color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.testTag("artist_artwork_missing_summary"))
        }
    }
}

/**
 * One photograph slot: the image when it really exists, a neutral monogram when it
 * does not, and the single word that says which of the five situations applies.
 *
 * [compact] is set when the card shares a row with its sibling. There the text column is
 * only ~85 dp wide at 390 dp, so the action moves under the row and owns the full card
 * width instead of breaking "fotografía" mid-word (§9.9).
 */
@Composable
fun ArtworkSlotCard(
    slot: ArtistPhotoSlot,
    artistName: String,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    actionsEnabled: Boolean = true,
) {
    // K48-R11: a file can be on disk and still fail to decode. Without this the slot kept
    // saying "Disponible" over an empty rectangle, which reads as a photograph that is
    // there and simply not shown. The card now reports the failure it actually saw.
    var imageUnreadable by remember(slot.imagePath) { mutableStateOf(false) }

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
        if (compact) {
            // Beside a sibling the text column would be ~85 dp at 390 dp: the status wrapped
            // onto four lines and the license ellipsized. Stacked, the photo keeps the card
            // width and the words keep the whole width under it (§9.9).
            ArtistSlotThumbnail(
                slot = slot,
                artistName = artistName,
                onUnreadable = { imageUnreadable = true },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            ArtistSlotDetailText(slot = slot, imageUnreadable = imageUnreadable)
            ArtistSlotChooseAction(slot = slot, onChoose = onChoose, fillWidth = true, enabled = actionsEnabled)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtistSlotThumbnail(
                    slot = slot,
                    artistName = artistName,
                    onUnreadable = { imageUnreadable = true },
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ArtistSlotDetailText(slot = slot, imageUnreadable = imageUnreadable)
                    ArtistSlotChooseAction(slot = slot, onChoose = onChoose, fillWidth = false, enabled = actionsEnabled)
                }
            }
        }
    }
}

/**
 * The words of a slot: its state, the real reason when it is blocked, and the provenance
 * of an image that actually exists. Provenance never stands in for the image.
 */
@Composable
private fun ArtistSlotDetailText(slot: ArtistPhotoSlot, imageUnreadable: Boolean = false) {
    // K48-R12-07: a saved photograph whose preview cannot be rendered is not "Disponible".
    // The file is still there, so the truthful state names exactly that distinction.
    val unshowable = slot.hasThumbnail && imageUnreadable
    Text(
        if (unshowable) "Guardada · no se puede mostrar" else slot.statusLabel,
        color = if (slot.isSatisfied && !unshowable) TertiaryCyan else PureWhite,
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
    slot.recovery?.let { recovery ->
        Text(recovery.explanation, color = TextSecondary, fontSize = 12.sp,
            modifier = Modifier.testTag("artist_artwork_recovery_${slot.slot.name}"))
    }
    if (slot.hasThumbnail && imageUnreadable) {
        // The photo is saved, so nothing is lost; only its preview failed. No download is
        // offered here: reopening the local file is the whole repair.
        // Beside a sibling the card is ~145 dp wide, and the two-line budget cut this
        // sentence mid-word. It now says only what is true, in a budget that holds at
        // every certified width: the file is there, the preview is not.
        Text(
            "La imagen guardada no se pudo abrir.",
            color = TextSecondary, fontSize = 10.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("artist_artwork_decode_error_${slot.slot.name}"),
        )
    }
    // Provenance of a real image only; it never substitutes the image. Author and license
    // get a line each so the short license is not the part that gets ellipsized (§9.6).
    if (slot.hasThumbnail) {
        val author = slot.attribution?.takeIf { it.isNotBlank() }
        val license = slot.license?.takeIf { it.isNotBlank() }
        author?.let {
            Text(
                it, color = TextSecondary, fontSize = 10.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        license?.let {
            Text(
                it, color = TextSecondary, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The one explicit choice this slot offers while it is still missing. */
@Composable
private fun ArtistSlotChooseAction(
    slot: ArtistPhotoSlot,
    onChoose: () -> Unit,
    fillWidth: Boolean,
    enabled: Boolean,
) {
    if (slot.isSatisfied) return
    TextButton(
        onClick = onChoose,
        enabled = enabled,
        modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 48.dp)
            .testTag("artist_artwork_slot_action_${slot.slot.name}"),
    ) {
        Text(slot.recovery?.actionLabel ?: "Elegir fotografía", color = SecondaryPurple, fontSize = 12.sp)
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
    modifier: Modifier = Modifier.size(SlotThumbnailSize),
    onUnreadable: () -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    // A saved file that Coil cannot decode must not look like a photo that is simply not
    // drawn: the card falls back to the neutral monogram and says so (§9.5, K48-R11).
    var decodeFailed by remember(slot.imagePath) { mutableStateOf(false) }
    LaunchedEffect(decodeFailed) { if (decodeFailed) onUnreadable() }
    // K48-R12-07: when the pixels cannot be shown the announcement drops the availability
    // word entirely. Saying "Disponible" next to an empty frame is the one thing a screen
    // reader must never hear about a preview that is not there.
    val describe = when {
        !slot.hasThumbnail -> "${slot.title}: ${slot.statusLabel}. Sin imagen verificada."
        decodeFailed -> "${slot.title}. Guardada, pero la vista previa no está disponible."
        else -> "${slot.title}: ${slot.statusLabel}"
    }
    if (slot.hasThumbnail && !decodeFailed) {
        val context = LocalContext.current
        val file = remember(slot.imagePath) { File(requireNotNull(slot.imagePath)) }
        // A 72-180 dp thumbnail gains nothing from a hardware bitmap, and some device decoders
        // fail on one without reporting anything useful. Software decoding is cheap here.
        val request = remember(file) {
            ImageRequest.Builder(context).data(file).allowHardware(false).build()
        }
        AsyncImage(
            model = request,
            contentDescription = describe,
            contentScale = ContentScale.Crop,
            onState = { state -> if (state is AsyncImagePainter.State.Error) decodeFailed = true },
            modifier = modifier
                .clip(shape)
                .testTag("artist_artwork_thumbnail_${slot.slot.name}"),
        )
    } else {
        ArtistSlotMonogram(
            artistName = artistName,
            describe = describe,
            tag = if (decodeFailed) {
                "artist_artwork_decode_fallback_${slot.slot.name}"
            } else {
                "artist_artwork_placeholder_${slot.slot.name}"
            },
            shape = shape,
            modifier = modifier,
        )
    }
}

/** A neutral initial: it never pretends to be a recovered photograph. */
@Composable
private fun ArtistSlotMonogram(
    artistName: String,
    describe: String,
    tag: String,
    shape: Shape,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(PureWhite.copy(alpha = 0.08f))
            .semantics { contentDescription = describe }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            artistName.trim().take(1).ifBlank { "?" }.uppercase(),
            color = TextSecondary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
        )
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
