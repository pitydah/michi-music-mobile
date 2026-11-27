package org.michimusic.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import org.michimusic.mobile.artist.canonicalArtistMbid
import org.michimusic.mobile.artist.exactArtistTrackIdentity
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet

/** One library entry; raw URI and trackIdentity never appear in visible text. */
internal data class ArtistTrackReviewRow(
    val title: String,
    val albumLabel: String,
    val claim: ArtistExactOccurrenceClaim,
    val candidateMbids: List<String>,
    val alreadyConfirmed: Boolean = false,
)

/** D43: render only albums where two plausible identities share one literal credit.
 * A raw tag merely offers a review candidate, never automatic identity authority. */
internal data class ArtistTrackReviewSelection(
    val rows: List<ArtistTrackReviewRow> = emptyList(),
    val omittedAlbums: Int = 0,
    val catalogLimitExceeded: Boolean = false,
)

internal fun sameAlbumTrackReviewRows(
    libraryId: String?, albums: List<LocalAlbum>,
    identities: ArtistExactOccurrenceIdentityResult.Ready?,
    includeAlreadyConfirmed: Boolean = false,
): List<ArtistTrackReviewRow> = selectSameAlbumTrackReviewRows(
    libraryId, albums, identities, includeAlreadyConfirmed).rows

internal fun selectSameAlbumTrackReviewRows(
    libraryId: String?, albums: List<LocalAlbum>,
    identities: ArtistExactOccurrenceIdentityResult.Ready?,
    includeAlreadyConfirmed: Boolean = false,
): ArtistTrackReviewSelection {
    if (libraryId.isNullOrBlank() || libraryId == "SCOPE_ALL" || identities == null)
        return ArtistTrackReviewSelection()
    // Same bound as the authoritative identity reader; no smaller arbitrary global cap.
    if (albums.sumOf { maxOf(1, it.tracks.size).toLong() } >
        ArtistExactOccurrenceIdentityPolicy.MAX_OCCURRENCES)
        return ArtistTrackReviewSelection(catalogLimitExceeded = true)
    val duplicateAlbums = albums.groupingBy { it.album.id }.eachCount()
    val result = mutableListOf<ArtistTrackReviewRow>()
    var omitted = 0
    for (album in albums) {
        // A malformed album cannot suppress all other reviewable albums.
        if (album.album.id.isBlank() || duplicateAlbums[album.album.id] != 1 ||
            album.tracks.size > 1_024 || album.tracks.any { it.id.isBlank() } ||
            album.tracks.map { it.id }.distinct().size != album.tracks.size) {
            omitted++
            continue
        }
        val candidates = album.tracks.map { track ->
            val identity = identities.identities[ArtistExactOccurrenceKey(album.album.id,
                exactArtistTrackIdentity(track))]
            track to identity
        }
        val byCredit = candidates.groupBy { (track, _) ->
            track.artist.ifBlank { album.album.artist }.trim()
        }
        for ((credit, credited) in byCredit) {
            if (credit.isBlank()) continue
            val offered = credited.flatMap { (_, evidence) ->
                evidence?.untrustedTagMbids.orEmpty() + listOfNotNull(evidence?.confirmedAlbumMbid)
            }.mapNotNull(::canonicalArtistMbid).distinct().sorted()
            if (offered.size !in 2..16 || credited.any { it.first.id.isBlank() }) continue
            for ((track, identity) in credited) {
                // A partial observation cannot confer a reviewable occurrence on a sibling.
                if (identity == null || (!includeAlreadyConfirmed && identity.confirmedByExactTrackChoice)) continue
                result += ArtistTrackReviewRow(
                    track.title.ifBlank { "Pista sin título" }, album.album.title.ifBlank { "Álbum" },
                    ArtistExactOccurrenceClaim(libraryId,album.album.id,album.album.artist,credit,
                        exactArtistTrackIdentity(track),track.id,artistTrackContentSignature(track)),
                    offered,
                    alreadyConfirmed = identity.confirmedByExactTrackChoice,
                )
            }
        }
    }
    return ArtistTrackReviewSelection(result, omittedAlbums = omitted)
}

/**
 * D49: resolve candidates for ONE exact album/track without removing a confirmed row.
 * The initial review queue and post-confirmation revalidation are different queries.
 * This reads the CURRENT in-memory album membership only. No SAF, tags, HTTP,
 * directory traversal, or implicit MusicBrainz identity resolution is performed.
 * Duplicate album IDs / track claims are rejected rather than picking one.
 */
internal fun offeredMbidsForExactTrack(
    libraryId: String?,
    albums: List<LocalAlbum>,
    identities: ArtistExactOccurrenceIdentityResult.Ready?,
    claim: ArtistExactOccurrenceClaim,
    includeAlreadyConfirmed: Boolean,
): List<String>? {
    if (libraryId.isNullOrBlank() || identities == null || claim.libraryId != libraryId ||
        claim.albumId.isBlank() || claim.trackId.isNullOrBlank()
    ) return null
    // Only examine one album's tracks; never rebuild all review rows.
    val matchingAlbums = albums.asSequence().filter { it.album.id == claim.albumId }
        .take(2).toList()
    if (matchingAlbums.size != 1) return null
    return sameAlbumTrackReviewRows(libraryId, matchingAlbums, identities,
        includeAlreadyConfirmed = includeAlreadyConfirmed)
        .singleOrNull { it.claim == claim }?.candidateMbids
}

/** This surface has no network side-effects; clicking a MusicBrainz link is explicit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistSameAlbumTrackReviewHost(
    libraryId: String?,
    albums: List<LocalAlbum>,
    identities: ArtistExactOccurrenceIdentityResult.Ready?,
    rootAuthority: MusicRootAuthority,
    onConfirmed: () -> Unit,
    onSessionProof: (ArtistTrackSessionProof) -> Boolean = { false },
) {
    val selection = remember(libraryId, albums, identities) {
        selectSameAlbumTrackReviewRows(libraryId, albums, identities, includeAlreadyConfirmed = true)
    }
    val rows = selection.rows
    // Read updated state at commit time. A coroutine launched from an older
    // composition must not silently confirm a track from an obsolete sheet.
    val latestAlbums by rememberUpdatedState(albums)
    val latestIdentities by rememberUpdatedState(identities)
    val latestOnSessionProof by rememberUpdatedState(onSessionProof)
    val context = LocalContext.current
    val enteredStamp by rootAuthority.reviewStamp.collectAsStateWithLifecycle()
    val enteredRevision by rootAuthority.revision.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    var expanded by remember(libraryId, enteredStamp, enteredRevision) { mutableStateOf(false) }
    var page by remember(libraryId, enteredStamp, enteredRevision) { mutableIntStateOf(0) }
    var message by remember(libraryId, enteredStamp, enteredRevision) { mutableStateOf<String?>(null) }
    if (selection.catalogLimitExceeded || selection.omittedAlbums > 0) {
        Text(if (selection.catalogLimitExceeded)
            "La revisión supera el límite de pertenencias verificables. No se ha confirmado ninguna identidad."
            else "${selection.omittedAlbums} álbumes no pueden revisarse por pertenencias duplicadas o tamaño. Los demás siguen disponibles.",
            fontSize = 12.sp, modifier = Modifier.testTag("artist_exact_track_review_limits"))
    }
    if (rows.isEmpty() && !expanded) return
    OutlinedButton(
        onClick = { expanded = true },
        enabled = rows.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .heightIn(min = 48.dp).testTag("artist_exact_track_review_toggle"),
    ) { Text("Revisar homónimos por canción (${rows.size})") }
    if (expanded) MichiBottomSheet(onDismissRequest = { expanded = false }) {
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
        Column(Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Revisión individual de pistas",fontSize = 16.sp)
        Text("Una etiqueta no confirma una identidad. Comprueba cada MBID antes de decidir; " +
            "solo se cambiará la asociación local de esa pista.", fontSize = 12.sp)
        if (rows.isEmpty()) {
            Text(if (identities == null) "Actualizando las evidencias de identidad…"
                else "Ya no hay pistas revisables en este ámbito.", fontSize = 12.sp)
            return@Column
        }
        val count = (rows.size + 7) / 8
        val safePage = page.coerceIn(0, count - 1)
        Text("Página ${safePage + 1} de $count",fontSize = 12.sp,
            modifier = Modifier.testTag("artist_exact_track_review_page"))
        rows.drop(safePage * 8).take(8).forEach { row ->
            val key = ArtistExplicitTrackChoicePolicy.trackDigest(row.claim)
            var selected by remember(key, row.candidateMbids, enteredStamp) { mutableStateOf<String?>(null) }
            var accepted by remember(key, enteredStamp) { mutableStateOf(false) }
            var saving by remember(key, enteredStamp) { mutableStateOf(false) }
            var checking by remember(key, enteredStamp) { mutableStateOf(false) }
            Text("${row.title} · ${row.albumLabel}",fontSize = 13.sp)
            if (row.alreadyConfirmed) {
                Text("Identidad guardada. Puedes revalidar sus bytes sin cambiar el MBID.",
                    fontSize = 12.sp, modifier = Modifier.testTag("artist_exact_track_stored"))
            }
            if (!row.alreadyConfirmed) {
            row.candidateMbids.forEach { mbid ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = selected == mbid, onClick = { selected = mbid; accepted = false },
                        modifier = Modifier.testTag("artist_exact_track_option_${mbid}"))
                    TextButton(onClick = { uriHandler.openUri("https://musicbrainz.org/artist/$mbid") },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Comprobar identidad ${mbid.take(8)}…",fontSize = 12.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min=48.dp)
                    .toggleable(value=accepted, role=Role.Checkbox, onValueChange={ accepted=it }),
                horizontalArrangement=Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked=accepted,onCheckedChange=null)
                Text("He verificado la identidad de esta pista",fontSize=12.sp)
            }
            }
            // Read only this exact stored association. A missing catalogue SHA
            // cannot be repaired by scanning other tracks or guessing by artist name.
            OutlinedButton(
                onClick = {
                    if (checking || saving) return@OutlinedButton
                    checking = true
                    coroutineScope.launch {
                        val verified = try {
                            withContext(Dispatchers.IO) {
                                ArtistOnDemandTrackRevalidator(
                                    ArtistEnrichmentPersistence.database(context),
                                    currentAuthority = {
                                        val fresh = rootAuthority.currentReviewStamp()
                                        ArtistTrackChoiceAuthority(requireNotNull(libraryId),
                                            fresh.sourceId, fresh.epoch, rootAuthority.revision.value)
                                    },
                                    currentClaim = {
                                        // Current authorized in-memory snapshot: no MediaStore,
                                        // SAF traversal or full library audio reread.
                                        latestAlbums.asSequence()
                                            .filter { it.album.id == row.claim.albumId }
                                            .flatMap { album -> album.tracks.asSequence()
                                                .filter { it.id == row.claim.trackId }
                                                .map { track -> ArtistExactOccurrenceClaim(
                                                    requireNotNull(libraryId), album.album.id,
                                                    album.album.artist,
                                                    track.artist.ifBlank { album.album.artist },
                                                    exactArtistTrackIdentity(track), track.id,
                                                    artistTrackContentSignature(track),
                                                ) } }
                                            .take(2).toList().singleOrNull()
                                    },
                                    currentOfferedMbids = {
                                        offeredMbidsForExactTrack(
                                            libraryId, latestAlbums, latestIdentities, row.claim,
                                            includeAlreadyConfirmed = true,
                                        )
                                    },
                                    sourceProof = { claim ->
                                        ArtistTrackSourceProofReader(context).read(claim)
                                    },
                                ).verify(row.claim)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        if (verified == null) {
                            message = "No fue posible revalidar esa selección guardada. " +
                                "Comprueba el archivo y su pertenencia a la biblioteca."
                        } else {
                            message = if (latestOnSessionProof(verified))
                                "Selección revalidada con los bytes actuales, solo en esta sesión. No modifica las etiquetas ni el catálogo."
                            else "La sesión cambió o alcanzó su límite de evidencias. No se descartaron comprobantes anteriores."
                        }
                        checking = false
                    }
                },
                enabled = !saving && !checking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("artist_exact_track_revalidate"),
            ) { Text(if (checking) "Verificando pista…" else "Revalidar selección guardada") }
            if (!row.alreadyConfirmed) Button(
                onClick = {
                    val chosen = selected ?: return@Button
                    if (saving) return@Button
                    saving = true
                    coroutineScope.launch {
                        val outcome = try {
                            withContext(Dispatchers.IO) {
                                // Bind to the timestamp/revision actually shown in UI,
                                // NOT to fresh metadata at click time (stale-page hazard).
                                val stamp = enteredStamp
                                val revision = enteredRevision
                                val req = ArtistTrackChoiceRequest(
                                    libraryId = requireNotNull(libraryId),albumId=row.claim.albumId,
                                    trackId=requireNotNull(row.claim.trackId),literalCredit=row.claim.literalCredit,
                                    trackIdentity=row.claim.trackIdentity,chosenMbid=chosen,
                                    offeredMbids=row.candidateMbids,rootSourceId=stamp.sourceId,
                                    rootEpoch=stamp.epoch,catalogRevision=revision,confirmedByUser=true)
                                ArtistExplicitTrackChoiceStore(
                                    ArtistEnrichmentPersistence.database(context),
                                    currentAuthority = {
                                        val fresh = rootAuthority.currentReviewStamp()
                                        ArtistTrackChoiceAuthority(requireNotNull(libraryId),fresh.sourceId,
                                            fresh.epoch,rootAuthority.revision.value)
                                    },
                                    currentSourceProof = { exactClaim ->
                                        ArtistTrackSourceProofReader(context).read(exactClaim)
                                    },
                                    currentLiveReview = { requested ->
                                        val candidates = offeredMbidsForExactTrack(
                                            libraryId, latestAlbums, latestIdentities, row.claim,
                                            includeAlreadyConfirmed = false,
                                        )
                                        candidates?.let { ArtistTrackChoiceLiveReview(row.claim, it) }
                                    },
                                ).confirmFirst(req,row.claim)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                        message = when {
                            !outcome -> "No se guardó: comprueba el acceso, el tamaño y la identidad actual de la pista."
                            strongCatalogHashIsPresent(row.claim.localContentSignature) ->
                                "Pista confirmada con huella de contenido. Actualizando biblioteca…"
                            else -> "Elección guardada con SHA-256, pero aún no puede mostrarse como " +
                                "identidad verificada: falta un hash fuerte vigente en el catálogo."
                        }
                        if (outcome) onConfirmed()
                        saving = false
                    }
                },
                enabled = selected != null && accepted && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("artist_exact_track_confirm"),
            ) { Text("Confirmar solo esta pista") }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            OutlinedButton(onClick={page=(safePage-1).coerceAtLeast(0)},enabled=safePage>0,
                modifier=Modifier.heightIn(min=48.dp)) { Text("Anteriores") }
            OutlinedButton(onClick={page=(safePage+1).coerceAtMost(count-1)},enabled=safePage<count-1,
                modifier=Modifier.heightIn(min=48.dp)) { Text("Siguientes") }
        }
        message?.let { Text(it, fontSize=12.sp,modifier=Modifier.testTag("artist_exact_track_status")) }
        }
    }
}
