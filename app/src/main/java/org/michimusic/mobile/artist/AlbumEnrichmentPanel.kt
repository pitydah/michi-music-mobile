package org.michimusic.mobile.artist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Album history is optional external documentation for a manually confirmed release GROUP.
 * It cannot mutate Track, Album, the local MediaStore, or ArtistEnrichmentPersistence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumEnrichmentPanel(
    localAlbumTitle: String,
    localArtist: String,
    localAlbumId: String,
    localTrackIds: List<String>,
    libraryId: String?,
    onDismiss: () -> Unit,
    gateway: AlbumEnrichmentGateway = AlbumEnrichmentService,
    selectionStore: AlbumEnrichmentSelectionStore? = null,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val store = remember(context, selectionStore) { selectionStore ?: AlbumEnrichmentPersistence.from(context) }
    val scopeKey = remember(libraryId, localAlbumId, localTrackIds, localAlbumTitle, localArtist) {
        albumEnrichmentScopeKey(libraryId, localAlbumId, localTrackIds, localAlbumTitle, localArtist)
    }
    val panelKey = remember(libraryId, localAlbumId, localTrackIds, localAlbumTitle, localArtist) {
        albumEnrichmentPanelKey(libraryId, localAlbumId, localTrackIds, localAlbumTitle, localArtist)
    }
    // Even an unresolved ALL scope must invalidate state when the actual local album changes.
    // Otherwise a homonymous album can inherit another panel's visible remote profile.
    var candidates by remember(panelKey) {
        mutableStateOf<List<AlbumHistoryCandidate>?>(null)
    }
    var profile by remember(panelKey) {
        mutableStateOf<AlbumHistoricalProfile?>(null)
    }
    var selectedId by remember(panelKey) { mutableStateOf<String?>(null) }
    var stale by remember(panelKey) { mutableStateOf(false) }
    var loading by remember(panelKey) { mutableStateOf(true) }
    var failed by remember(panelKey) { mutableStateOf(false) }
    var warning by remember(panelKey) { mutableStateOf<String?>(null) }
    var candidateRequest by remember(panelKey) { mutableIntStateOf(0) }
    var profileRequest by remember(panelKey) { mutableIntStateOf(0) }

    // Opening the sheet is the explicit action. A verified cache avoids new network I/O.
    LaunchedEffect(panelKey, store) {
        loading = true
        failed = false
        warning = null
        try {
            val cached = scopeKey?.let { store.load(it) }
            if (cached != null) {
                profile = cached.profile
                stale = cached.stale
                loading = false
            } else {
                candidateRequest++
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { candidateRequest++ }
    }

    LaunchedEffect(candidateRequest, gateway, panelKey) {
        if (candidateRequest == 0) return@LaunchedEffect
        loading = true
        failed = false
        candidates = null
        try {
            candidates = gateway.findAlbumCandidates(localAlbumTitle, localArtist)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            failed = true
            if (profile != null) warning = "La búsqueda falló; se mantienen los datos confirmados previamente."
        }
        finally { loading = false }
    }

    LaunchedEffect(selectedId, profileRequest, gateway, panelKey) {
        val id = selectedId ?: return@LaunchedEffect
        if (selectedReleaseGroupMbid(candidates.orEmpty(), id) != id) return@LaunchedEffect
        loading = true
        failed = false
        warning = null
        try {
            val loaded = gateway.albumHistory(id)
            require(canonicalAlbumMbid(loaded.mbid) == id) { "Identidad MusicBrainz inconsistente" }
            require(albumHistoryPayloadSafe(loaded)) { "Información histórica sin fuente o identidad verificable" }
            profile = loaded
            stale = false
            if (scopeKey != null) {
                try { store.save(scopeKey, loaded) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { warning = "Información visible, pero no se pudo guardar para uso posterior." }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            failed = true
            if (profile != null) warning = "No se pudo actualizar; se conservan los datos confirmados."
        }
        finally { loading = false }
    }

    MichiBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("album_enrichment_popup"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Historia del álbum", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("$localAlbumTitle · $localArtist", color = TextSecondary, fontSize = 13.sp)
            Text("Información externa de MusicBrainz. Un grupo de lanzamientos no identifica una edición concreta ni modifica los archivos.",
                color = TextSecondary, fontSize = 12.sp)
            if (scopeKey == null) {
                Text("La asociación no se guardará: selecciona una biblioteca individual para evitar mezclar álbumes homónimos.",
                    color = TextSecondary, fontSize = 12.sp)
            }
            if (loading && profile == null) {
                CircularProgressIndicator(color = PrimaryPinkContainer)
            } else if (failed && profile == null) {
                Text("No se pudo consultar la información histórica.", color = TextSecondary)
                TextButton(onClick = {
                    if (selectedId != null) profileRequest++ else candidateRequest++
                }) { Text("Reintentar", color = PrimaryPinkContainer) }
            } else if (profile != null) {
                val result = requireNotNull(profile)
                if (loading) Text("Actualizando información; conservamos la ficha existente.", color = TextSecondary)
                if (stale) Text("Información guardada; puede estar desactualizada.", color = TextSecondary)
                Text(result.title, color = PureWhite, fontWeight = FontWeight.SemiBold)
                result.firstReleaseDate?.let { Text("Primera publicación registrada: $it", color = TextSecondary) }
                if (result.disambiguation.isNotBlank()) {
                    Text("Nota de catálogo: ${result.disambiguation}", color = TextSecondary)
                }
                Text(result.description ?: "No existe una reseña histórica verificable vinculada a este álbum.",
                    color = PureWhite, fontSize = 14.sp)
                result.wikipediaUrl?.takeIf(::allowedWikipediaSource)?.let { url ->
                    TextButton(onClick = { uriHandler.openUri(url) }) {
                        Text("Fuente: Wikipedia", color = PrimaryPinkContainer)
                    }
                }
                TextButton(onClick = { uriHandler.openUri(result.musicBrainzUrl) }) {
                    Text("Ficha de MusicBrainz (grupo de lanzamientos)", color = PrimaryPinkContainer)
                }
                TextButton(onClick = {
                    // This exact MBID was previously confirmed by the user and persisted.
                    // A refresh is explicit, never triggered on mosaic navigation.
                    candidates = listOf(AlbumHistoryCandidate(result.mbid, result.title,
                        localArtist, result.firstReleaseDate, result.disambiguation))
                    selectedId = canonicalAlbumMbid(result.mbid)
                    profileRequest++
                }, enabled = !loading) { Text("Actualizar información", color = PrimaryPinkContainer) }
                TextButton(onClick = {
                    selectedId = null
                    profile = null
                    candidateRequest++
                }) { Text("Buscar otro álbum", color = TextSecondary) }
            } else if (candidates.isNullOrEmpty()) {
                Text("No se encontraron grupos de lanzamientos coincidentes con este título y artista.",
                    color = TextSecondary)
                TextButton(onClick = { candidateRequest++ }) {
                    Text("Volver a buscar", color = PrimaryPinkContainer)
                }
            } else {
                Text("Seleccionar álbum en MusicBrainz", color = PureWhite, fontWeight = FontWeight.SemiBold)
                candidates.orEmpty().forEach { candidate ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            selectedId = selectedReleaseGroupMbid(candidates.orEmpty(), candidate.mbid)
                            failed = false
                        }.padding(vertical = 8.dp),
                    ) {
                        Text(candidate.title, color = PureWhite, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(candidate.creditedArtist, candidate.firstReleaseDate,
                            candidate.disambiguation.takeIf { it.isNotBlank() }).joinToString(" · "),
                            color = TextSecondary, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.18f))
                }
            }
            warning?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
            TextButton(onClick = onDismiss) { Text("Cerrar", color = TextSecondary) }
        }
    }
}
