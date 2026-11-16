package org.michimusic.mobile.artist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K48-R07 / §9.8: advanced actions live in their own content component.
 *
 * The destructive removal is an advanced operation, so it sits behind both this
 * collapsed section and the explicit [EnrichmentDeleteState.Confirming] receipt that
 * K48-R05 introduced. The confirmation states exactly what is removed and what stays,
 * and only the user's explicit confirm reaches the controller.
 *
 * Pure content: no Koin, no Room, no network, no controller reference.
 */
@Composable
internal fun ArtistEnrichmentAdvancedActionsContent(
    artistCredit: String,
    mbid: String,
    albumCount: Int,
    refreshing: Boolean,
    delete: EnrichmentDeleteState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondaryColors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryPurple)
    val secondaryBorder = BorderStroke(0.8.dp, SecondaryPurple.copy(alpha = 0.5f))
    Column(modifier = modifier) {
        TextButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("artist_enrichment_advanced"),
        ) {
            Text(
                if (expanded) "Ocultar opciones avanzadas" else "Opciones avanzadas",
                color = PureWhite, fontSize = 12.sp,
            )
        }
        if (expanded) {
            // K48-S7 design E: the external refresh is an advanced action,
            // not the primary affordance of the summary.
            TextButton(
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("artist_enrichment_refresh"),
            ) {
                Text(
                    if (refreshing) "Actualizando…" else "Actualizar información externa",
                    color = TertiaryCyan, fontSize = 12.sp,
                )
            }
            if (delete is EnrichmentDeleteState.Confirming) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .testTag("artist_enrichment_confirm_delete"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Se quitarán de $artistCredit los datos enriquecidos guardados " +
                            "(MBID $mbid, biografía, fotografías y vínculos de " +
                            "$albumCount álbum(es)). Tus archivos de audio, " +
                            "etiquetas y la biblioteca local no se modifican.",
                        color = PureWhite, fontSize = 12.sp,
                    )
                    Text(
                        "Esta acción no se puede deshacer desde aquí.",
                        color = TextSecondary, fontSize = 11.sp,
                    )
                    TextButton(
                        onClick = onCancelDelete,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_enrichment_delete_cancel"),
                    ) { Text("Cancelar", color = SecondaryPurple) }
                    OutlinedButton(
                        onClick = onConfirmDelete,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("artist_enrichment_delete_confirm"),
                        colors = secondaryColors,
                        border = secondaryBorder,
                    ) { Text("Quitar datos enriquecidos") }
                }
            } else {
                OutlinedButton(
                    onClick = onRequestDelete,
                    enabled = delete !is EnrichmentDeleteState.Deleting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("artist_enrichment_clear"),
                    colors = secondaryColors,
                    border = secondaryBorder,
                ) {
                    Text(
                        if (delete is EnrichmentDeleteState.Deleting) "Borrando…"
                        else "Quitar datos enriquecidos…",
                    )
                }
            }
        }
    }
}
