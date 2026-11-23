package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import org.koin.compose.koinInject
import org.michimusic.core.intelligence.MichiGenreAccessPolicy
import org.michimusic.data.metadata.MetadataPrivateBundleCoordinator
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.playback.HandoffState
import org.michimusic.mobile.playback.PlaybackSessionManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.ExtendedTagUnavailableReason
import org.michimusic.data.repository.MetadataEditorEvidenceResult
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.TextSecondary

/** The existing verified cold reader is the only source; no file I/O in composition. */
@Composable
internal fun MichiMetadataEditorPreviewEntry(
    track: Track,
    repository: ExtendedTrackMetadataRepository,
    onDismiss: () -> Unit,
    onDirtyChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scopeStore: LibraryScopeStore = koinInject()
    val rootAuthority: MusicRootAuthority = koinInject()
    val session: PlaybackSessionManager = koinInject()
    // Recomposition observes live playback; callbacks always re-read the source of truth.
    val currentSession by session.sessionState.collectAsStateWithLifecycle()
    val currentSessionAllowsPreparation = currentSession.currentTrack?.filepath != track.filepath &&
        !currentSession.isRemoteActive && currentSession.handoffState == HandoffState.IDLE &&
        currentSession.activeEndpoint.isLocal
    val token = remember(track.sourceId, track.id, track.filepath, track.size) { Any() }
    val capturedScope = remember(token) { scopeStore.activeScope.value }
    val capturedRevision = remember(token) { rootAuthority.revision.value }
    val open = remember(token) { AtomicBoolean(true) }
    DisposableEffect(token) {
        open.set(true)
        onDispose { open.set(false) }
    }
    val bundle = remember(context, repository) {
        MetadataPrivateBundleCoordinator(context.applicationContext, repository)
    }
    fun authority() = MichiGenreAccessPolicy.Context(
        trackLocal = track.source == TrackSource.LOCAL,
        receiptMatches = open.get() && track.id.isNotBlank(),
        uriMatches = track.filepath.startsWith("content://"),
        scopeUnchanged = scopeStore.activeScope.value == capturedScope,
        revisionUnchanged = rootAuthority.revision.value == capturedRevision,
        dialogOpen = open.get(),
        trackSelectedInPlayer = session.sessionState.value.currentTrack?.filepath == track.filepath,
        remoteSessionActive = session.sessionState.value.isRemoteActive,
        handoffActive = session.sessionState.value.handoffState != HandoffState.IDLE,
        localEndpoint = session.sessionState.value.activeEndpoint.isLocal,
    )
    // Private staging deliberately reuses the stricter mutation preflight; it does
    // NOT claim to certify physical FileDescriptor ownership or provider write safety.
    val stillOwns = { MichiGenreAccessPolicy.canLookup(authority()) }
    val playbackClosed = { MichiGenreAccessPolicy.canMutateAudioFile(authority()) }
    var result by remember(track.sourceId, track.id, track.filepath, track.size) {
        mutableStateOf<EditorLoadState>(EditorLoadState.Loading)
    }
    LaunchedEffect(track.sourceId, track.id, track.filepath, track.size, track.source) {
        result = if (track.source != TrackSource.LOCAL) EditorLoadState.RemoteReadOnly
        else {
            try {
                when (val read = repository.loadVerifiedEditorSnapshotResult(
                    track.sourceId, track.id, track.filepath, track.size)) {
                    is MetadataEditorEvidenceResult.Verified -> EditorLoadState.Ready(read.snapshot)
                    is MetadataEditorEvidenceResult.Unavailable -> EditorLoadState.Unavailable(read.reason)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                EditorLoadState.Unavailable(ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE)
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        when (val state = result) {
            EditorLoadState.Loading -> EditorNotice("Leyendo las etiquetas del archivo…", onDismiss)
            EditorLoadState.RemoteReadOnly -> EditorNotice(
                "Las fuentes remotas son de solo lectura. No se modificará la biblioteca.", onDismiss)
            is EditorLoadState.Unavailable -> {
                EditorNotice("No se pudieron verificar los metadatos (${state.reason.name}). " +
                    "No se aplicará ningún cambio.", onDismiss)
                if (track.source == TrackSource.LOCAL && track.filepath.startsWith("content://")) {
                    MichiMetadataRecoveryPanel(
                        track.sourceId, track.id, track.filepath,
                        ownerStillCurrent = stillOwns,
                        playbackClosed = playbackClosed,
                    )
                }
            }
            is EditorLoadState.Ready -> MichiMetadataEditorPreviewContent(
                state.snapshot, onDismiss = onDismiss, onDirtyChanged = onDirtyChanged,
                privateBundleCoordinator = bundle,
                expectedFileBytes = track.size,
                stillOwnsPrivateSource = stillOwns,
                playbackDescriptorClosed = playbackClosed,
                currentSessionAllowsPreparation = currentSessionAllowsPreparation,
                recoveryPanel = if (track.source == TrackSource.LOCAL &&
                    track.filepath.startsWith("content://")) {
                    {
                        MichiMetadataRecoveryPanel(
                            track.sourceId, track.id, track.filepath,
                            ownerStillCurrent = stillOwns,
                            playbackClosed = playbackClosed,
                        )
                    }
                } else null)
        }
    }
}

private sealed interface EditorLoadState {
    data object Loading : EditorLoadState
    data object RemoteReadOnly : EditorLoadState
    data class Unavailable(val reason: ExtendedTagUnavailableReason) : EditorLoadState
    data class Ready(val snapshot: MetadataSnapshot) : EditorLoadState
}

@Composable
private fun EditorNotice(message: String, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("EDITOR DE METADATOS", style = MichiTypography.screenEyebrow)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        TextButton(onClick = onDismiss) { Text("Cerrar") }
    }
}
