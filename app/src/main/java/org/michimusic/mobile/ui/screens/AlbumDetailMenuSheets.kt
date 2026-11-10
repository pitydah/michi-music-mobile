package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.components.sampleRateLabel
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AlbumDetailMenuSheets(
    activeModal: AlbumDetailModal?,
    tracks: List<Track>,
    orderedTracks: List<Track>,
    onModalChange: (AlbumDetailModal?) -> Unit,
    onHideAlbum: (() -> Unit)? = null,
) {
    if (activeModal == AlbumDetailModal.MENU || activeModal == AlbumDetailModal.QUALITY) {
        MichiBottomSheet(onDismissRequest = { onModalChange(null) }) {
            AlbumDetailMenuSheetContent(
                activeModal = activeModal,
                tracks = tracks,
                orderedTracks = orderedTracks,
                onModalChange = onModalChange,
                onHideAlbum = onHideAlbum,
            )
        }
    }
}

@Composable
internal fun AlbumDetailMenuSheetContent(
    activeModal: AlbumDetailModal?,
    tracks: List<Track>,
    orderedTracks: List<Track>,
    onModalChange: (AlbumDetailModal?) -> Unit,
    onHideAlbum: (() -> Unit)? = null,
) {
    when (activeModal) {
        AlbumDetailModal.QUALITY -> LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .testTag("album_quality_popup"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "quality_intro") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Calidad de los archivos", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PureWhite)
                    Text(
                        "Datos de ${tracks.size} ${if (tracks.size == 1) "pista" else "pistas"} de la biblioteca.",
                        color = TextSecondary,
                    )
                }
            }
            itemsIndexed(orderedTracks, key = { index, track -> "quality_${track.id}_$index" }) { _, track ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    HorizontalDivider(color = TextMuted.copy(alpha = 0.25f))
                    Text(track.title, color = PureWhite, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(
                            albumCodecLabel(track)?.let { "Formato de archivo: $it" },
                            sampleRateLabel(track.sampleRate)?.let { "Frecuencia: $it" },
                            track.channels.takeIf { it in 1..8 }?.let { "Canales: $it" },
                        ).ifEmpty {
                            listOf("No hay parámetros técnicos suficientes en los metadatos.")
                        }.joinToString(" · "),
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
            item(key = "quality_footer") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Son metadatos declarados por los archivos; no describen la señal real de salida.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    TextButton(
                        modifier = Modifier.testTag("album_quality_close"),
                        onClick = { onModalChange(null) },
                    ) {
                        Text("Cerrar", color = TextSecondary)
                    }
                }
            }
        }

        AlbumDetailModal.MENU -> Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Opciones del álbum", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = PureWhite)
            TextButton(
                modifier = Modifier.testTag("album_menu_quality"),
                onClick = { onModalChange(albumModalForMenuAction(AlbumMenuAction.QUALITY)) },
            ) {
                Text("Información de calidad", color = PureWhite)
            }
            TextButton(onClick = { onModalChange(albumModalForMenuAction(AlbumMenuAction.HISTORY)) }) {
                Text("Cronología externa · MusicBrainz", color = PureWhite)
            }
            if (onHideAlbum != null) {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp).testTag("album_hide_collection_action"),
                    onClick = onHideAlbum,
                ) { Text("Quitar álbum de biblioteca", color = TextSecondary) }
            }
            TextButton(onClick = { onModalChange(albumModalForMenuAction(AlbumMenuAction.CLOSE)) }) {
                Text("Cerrar", color = TextSecondary)
            }
        }

        else -> Unit
    }
}
