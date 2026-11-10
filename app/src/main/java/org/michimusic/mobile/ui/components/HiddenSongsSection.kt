package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.data.cache.LibraryExclusionEntity
import org.michimusic.data.repository.LocalVisibilityResult
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/** Private metadata only: never displays a delete-file action or grants write access. */
@Composable
fun HiddenSongsSection(authority: MusicRootAuthority = koinInject()) {
    val root by authority.currentRoot.collectAsStateWithLifecycle()
    val revision by authority.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var rows by remember(root, revision) { mutableStateOf<List<LibraryExclusionEntity>>(emptyList()) }
    var isLoading by remember(root, revision) { mutableStateOf(true) }
    var error by remember(root, revision) { mutableStateOf<String?>(null) }
    var workingId by remember(root) { mutableStateOf<String?>(null) }
    LaunchedEffect(root, revision) {
        isLoading = true
        try {
            rows = authority.hiddenTracks()
            error = null
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "No se pudieron leer las canciones ocultas." }
        finally { isLoading = false }
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("library_hidden_songs"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Canciones ocultas", color = PureWhite,
            style = MaterialTheme.typography.titleMedium)
        Text("Solo se ocultan en Michi. Los archivos originales y las copias completas permanecen intactos.",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        when {
            isLoading -> Text("Cargando exclusiones…", color = TextSecondary)
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            rows.isEmpty() -> Text("No hay canciones ocultas en esta biblioteca.", color = TextSecondary)
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    .testTag("library_hidden_songs_list"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { "${it.sourceId}:${it.trackId}" }) { entry ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, color = PureWhite,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(entry.artist, color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(
                            enabled = workingId == null,
                            onClick = {
                                workingId = entry.trackId
                                scope.launch {
                                    try {
                                        when (val result = authority.restoreHiddenTrack(entry.trackId, entry.sourceId)) {
                                            LocalVisibilityResult.Restored -> Unit // revision reloads the list
                                            is LocalVisibilityResult.Rejected -> error = result.message
                                            else -> Unit
                                        }
                                    } catch (cancel: CancellationException) { throw cancel }
                                    catch (_: Exception) { error = "No se pudo restaurar la canción." }
                                    finally { workingId = null }
                                }
                            },
                            modifier = Modifier.testTag("library_restore_${entry.trackId}"),
                        ) { Text("Restaurar") }
                    }
                }
            }
        }
    }
}
