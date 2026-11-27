package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.artist.ArtistVariantHint
import org.michimusic.mobile.artist.ArtistVariantReason
import org.michimusic.mobile.artist.ArtistVariantReviewChoice
import org.michimusic.mobile.artist.displayLabel
import org.michimusic.mobile.ui.theme.SurfaceContainer
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.TertiaryCyan

/**
 * K48-S5 — possible spelling variants are shown, never silently merged. Each row states
 * why the credits were kept apart and offers the identity review that could later
 * justify a shared presentation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistVariantNotice(
    hints: List<ArtistVariantHint>,
    onReviewIdentity: (ArtistVariantReviewChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hints.isEmpty()) return
    var selectedHint by remember(hints) { mutableStateOf<ArtistVariantHint?>(null) }
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
            if (hints.any { it.credits.size == 1 })
                "Identidades incompatibles para el mismo nombre"
            else "Posibles variantes del mismo artista",
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
                    onClick = { selectedHint = hint },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("artist_variant_review_$index"),
                ) { Text("Revisar variantes", color = TertiaryCyan) }
            }
        }
    }
    selectedHint?.let { hint ->
        ModalBottomSheet(onDismissRequest = { selectedHint = null }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("¿Qué artista deseas revisar?", fontWeight = FontWeight.SemiBold)
                Text("Las coincidencias de nombre no confirman una identidad.",
                    color = TextSecondary, fontSize = 12.sp)
                hint.reviewChoices.forEachIndexed { index, choice ->
                    val status = when {
                        choice.conflictingEvidence && choice.verifiedMbid != null ->
                            "Grupo MBID observado · revisión por pista pendiente"
                        choice.conflictingEvidence -> "Pertenencias sin resolver · sin atribución"
                        choice.verifiedMbid != null -> "Identidad verificada"
                        else -> "Identidad pendiente"
                    }
                    TextButton(
                        onClick = {
                            selectedHint = null
                            onReviewIdentity(choice)
                        },
                        enabled = choice.libraryId != null && choice.albumIds.isNotEmpty() &&
                            !choice.conflictingEvidence,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_variant_choice_$index"),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.credit, color = TertiaryCyan, fontSize = 15.sp)
                            Text(status, color = TextSecondary, fontSize = 12.sp)
                            if (choice.conflictingEvidence) {
                                Text("${choice.albumIds.size} álbum(es) · ${choice.memberCount} pertenencias exactas",
                                    color = TextSecondary, fontSize = 11.sp)
                                choice.verifiedMbid?.let { mbid ->
                                    Text("MusicBrainz: $mbid", color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                if (hint.credits.size == 1) {
                    Text("Este nombre tiene pertenencias con identidad contradictoria. " +
                        "Los grupos mostrados son solo evidencia local: no se habilita la ficha " +
                        "editable hasta revisar cada álbum y pista.",
                        color = TextSecondary, fontSize = 12.sp)
                }
                if (hint.libraryId == null) {
                    Text("Selecciona primero una biblioteca concreta para revisar estas variantes.",
                        color = TextSecondary, fontSize = 12.sp)
                }
                TextButton(onClick = { selectedHint = null },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("artist_variant_dismiss")) {
                    Text("Cerrar")
                }
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
