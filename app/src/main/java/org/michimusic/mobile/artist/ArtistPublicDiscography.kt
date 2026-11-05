package org.michimusic.mobile.artist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.coverStyleFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/** Verifiable release-group facts; remote albums must NEVER be passed to playQueue. */
data class ArtistPublicAlbum(
    val releaseGroupMbid: String,
    val title: String,
    val year: Int?,
    val primaryType: String,
)
data class ArtistDiscographyPage(
    val albums: List<ArtistPublicAlbum>,
    val offset: Int,
    val nextOffset: Int,
    val total: Int,
) {
    val hasMore: Boolean get() = nextOffset < total && nextOffset <= 999
}
interface ArtistDiscographyGateway {
    suspend fun fetchAlbumGroups(confirmedMbid: String, offset: Int): ArtistDiscographyPage
}

/**
 * One row of the artist discography. A local album is playable and wins over its published
 * release-group twin, so the library and MusicBrainz never render the same album twice.
 */
data class ArtistDiscographyEntry(
    val title: String,
    val year: Int?,
    val local: LocalAlbum?,
    val releaseGroupMbid: String?,
    val primaryType: String?,
) {
    val playable: Boolean get() = local != null && local.tracks.isNotEmpty()
    val key: String get() = local?.album?.id?.let { "local_$it" }
        ?: "mb_${releaseGroupMbid ?: normalizedDiscographyTitle(title)}"
}

internal fun normalizedDiscographyTitle(value: String): String =
    java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")

/**
 * Merges the playable local albums with the published catalogue into one discography.
 * A published album whose normalized title (and compatible year) matches a local album is
 * the same entry: it keeps the local playback and records the release-group id as metadata.
 * Nothing remote becomes playable and no local album is dropped.
 */
fun mergeArtistDiscography(
    local: List<LocalAlbum>,
    published: List<ArtistPublicAlbum>,
): List<ArtistDiscographyEntry> {
    val entries = local.map { group ->
        ArtistDiscographyEntry(
            title = group.album.title,
            year = group.album.year.takeIf { it > 0 },
            local = group,
            releaseGroupMbid = null,
            primaryType = null,
        )
    }.toMutableList()
    val claimed = mutableSetOf<Int>()
    val publicOnly = published.mapNotNull { album ->
        val wanted = normalizedDiscographyTitle(album.title)
        val index = entries.indexOfFirst { entry ->
            normalizedDiscographyTitle(entry.title) == wanted &&
                (entry.year == null || album.year == null || entry.year == album.year)
        }
        if (index >= 0 && claimed.add(index)) {
            entries[index] = entries[index].copy(
                releaseGroupMbid = album.releaseGroupMbid,
                primaryType = album.primaryType,
            )
            null
        } else {
            ArtistDiscographyEntry(
                title = album.title,
                year = album.year,
                local = null,
                releaseGroupMbid = album.releaseGroupMbid,
                primaryType = album.primaryType,
            )
        }
    }
    return (entries + publicOnly).sortedWith(
        compareByDescending<ArtistDiscographyEntry> { it.year ?: 0 }
            .thenBy { normalizedDiscographyTitle(it.title) },
    )
}

/** MusicBrainz release-groups have no cover URLs; neither artwork nor years are invented. */
fun parseMusicBrainzAlbumGroups(data: JsonObject, requestedOffset: Int): ArtistDiscographyPage {
    val array = (data["release-groups"] as? JsonArray).orEmpty()
    val total = (data["release-group-count"] as? JsonPrimitive)?.intOrNull ?: array.size
    val groups = array.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = canonicalArtistMbid((row["id"] as? JsonPrimitive)?.contentOrNull)
            ?: return@mapNotNull null
        val title = (row["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(240)
            ?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val date = (row["first-release-date"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val year = date.take(4).toIntOrNull()?.takeIf { it in 1000..2100 }
        ArtistPublicAlbum(id, title, year,
            (row["primary-type"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }.distinctBy { it.releaseGroupMbid }
    return ArtistDiscographyPage(groups, requestedOffset,
        requestedOffset + array.size, total.coerceAtLeast(0))
}

/**
 * Screen-level state is kept ABOVE the outer LazyColumn, so a 1000-album catalogue
 * adds one lazily composed list item per release group rather than one giant Column.
 * No HTTP is sent until requestFirst() is explicitly called by the user.
 */
class ArtistPublicDiscographyState internal constructor(
    val mbid: String,
) {
    var requestedOffset by mutableIntStateOf(-1)
        private set
    var retryNonce by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        internal set
    var error by mutableStateOf(false)
        internal set
    var albums by mutableStateOf(emptyList<ArtistPublicAlbum>())
        internal set
    var nextOffset by mutableIntStateOf(0)
        internal set
    var total by mutableIntStateOf(0)
        internal set

    val hasMore: Boolean
        get() = !loading && !error && requestedOffset >= 0 &&
            nextOffset > requestedOffset && nextOffset < total && nextOffset <= 999 &&
            albums.size < 1000

    fun requestFirst() {
        if (requestedOffset < 0) requestedOffset = 0
    }
    fun requestNext() {
        if (hasMore) requestedOffset = nextOffset
    }
    fun retry() {
        if (requestedOffset >= 0 && !loading) retryNonce++
    }
}

@Composable
fun rememberArtistPublicDiscographyState(
    confirmedMbid: String?,
    gateway: ArtistDiscographyGateway = ArtistEnrichmentService.repository,
): ArtistPublicDiscographyState? {
    val mbid = canonicalArtistMbid(confirmedMbid) ?: return null
    val state = remember(mbid, gateway) { ArtistPublicDiscographyState(mbid) }
    LaunchedEffect(state, state.requestedOffset, state.retryNonce) {
        val offset = state.requestedOffset
        if (offset < 0) return@LaunchedEffect
        state.loading = true
        state.error = false
        try {
            val result = gateway.fetchAlbumGroups(mbid, offset)
            currentCoroutineContext().ensureActive()
            // A failed retry must not duplicate previously loaded release groups.
            state.albums = (state.albums + result.albums).distinctBy { it.releaseGroupMbid }
                .take(1000)
            state.nextOffset = result.nextOffset
            state.total = result.total
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.error = true
        } finally {
            state.loading = false
        }
    }
    return state
}

/** Entries are part of the PARENT LazyColumn: no nested unbounded scroller. */
fun LazyListScope.artistDiscographyItems(
    state: ArtistPublicDiscographyState?,
    localAlbums: List<LocalAlbum>,
    playableTrackIds: Set<String> = emptySet(),
    onPlayAlbum: (LocalAlbum) -> Unit = {},
) {
    item(key = "artist_discography_header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()
            .testTag("artist_discography")) {
            Text("DISCOGRAFÍA", color = PrimaryPinkContainer, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Los álbumes de tu biblioteca se reproducen; el catálogo de MusicBrainz es solo información.",
                color = TextSecondary, fontSize = 12.sp)
            if (state != null && state.requestedOffset < 0) {
                TextButton(onClick = state::requestFirst, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("artist_public_discography_load")) {
                    Text("Consultar catálogo publicado", color = TertiaryCyan)
                }
            }
        }
    }
    // One row per album: the local copy wins over its catalogue twin, so adding an album
    // to the library updates this same discography instead of creating a second list.
    val entries = mergeArtistDiscography(localAlbums, state?.albums.orEmpty())
    items(items = entries, key = { it.key }) { entry ->
        ArtistDiscographyRow(entry, playableTrackIds, onPlayAlbum)
    }
    if (state != null && state.requestedOffset >= 0) {
        item(key = "artist_discography_footer") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) CircularProgressIndicator(color = TertiaryCyan)
                if (state.error) {
                    Text("No se pudo consultar MusicBrainz. Tu colección local sigue disponible.",
                        color = TextSecondary, fontSize = 12.sp)
                    TextButton(onClick = state::retry, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("artist_public_retry")) { Text("Reintentar", color = TertiaryCyan) }
                } else if (!state.loading && state.albums.isEmpty()) {
                    Text("No hay álbumes publicados en esta consulta.", color = TextSecondary, fontSize = 12.sp)
                }
                if (state.hasMore) {
                    TextButton(onClick = state::requestNext, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("artist_public_next_page")) {
                        Text("Mostrar más álbumes", color = TertiaryCyan)
                    }
                }
                if (state.albums.isNotEmpty()) {
                    Text("${state.albums.size} de ${state.total} álbumes del catálogo · Fuente: MusicBrainz",
                        color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ArtistDiscographyRow(
    entry: ArtistDiscographyEntry,
    playableTrackIds: Set<String>,
    onPlayAlbum: (LocalAlbum) -> Unit,
) {
    val links = LocalUriHandler.current
    val local = entry.local
    val canPlay = local != null && local.tracks.any { it.id in playableTrackIds }
    val subtitle = buildString {
        entry.year?.let { append(it).append(" · ") }
        if (local != null) {
            append(if (local.tracks.size == 1) "1 canción" else "${local.tracks.size} canciones")
            append(" · En tu biblioteca")
        } else {
            append("Solo catálogo · MusicBrainz")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, enabled = local == null || canPlay) {
                if (local != null) {
                    onPlayAlbum(local)
                } else {
                    entry.releaseGroupMbid?.let { links.openUri("https://musicbrainz.org/release-group/$it") }
                }
            }
            .padding(vertical = 6.dp)
            .testTag(
                if (local != null) "artist_discography_local_${local.album.id}"
                else "artist_public_album_${entry.releaseGroupMbid}",
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (local != null) {
            AlbumArtView(
                coverStyle = coverStyleFor(local.album.coverId.ifBlank { local.album.title }),
                imageModel = local.album.coverId,
                fallbackMediaUri = local.tracks.firstOrNull()?.filepath,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                cornerRadius = 10.dp,
                borderWidth = 0.dp,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, color = PureWhite, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                color = if (local != null) TertiaryCyan else TextSecondary,
                fontSize = 11.sp,
            )
        }
        if (local != null && !canPlay) {
            Text("No disponible", color = TextSecondary, fontSize = 11.sp)
        }
    }
}

/** Standalone adapter for previews and existing Compose tests. */
@Composable
fun ArtistPublicDiscography(
    confirmedMbid: String,
    modifier: Modifier = Modifier,
    gateway: ArtistDiscographyGateway = ArtistEnrichmentService.repository,
) {
    val state = rememberArtistPublicDiscographyState(confirmedMbid, gateway) ?: return
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        artistDiscographyItems(state, emptyList())
    }
}
