package org.michimusic.mobile.ui.screens

import org.michimusic.mobile.playback.SettingsReplayGainGateway
import org.michimusic.mobile.playback.SettingsReplayGainEdit
import org.michimusic.mobile.playback.SettingsReplayGainSaveStatus
import org.michimusic.mobile.playback.settingsReplayGainTruth

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.koin.compose.koinInject
import org.michimusic.data.cache.AppDao
import org.michimusic.player.ReplayGainMode






/** Root is discovery only. Expensive owners and observers are not opened until needed. */
@Composable
internal fun SettingsHubLanding(onOpen: (SettingsSection) -> Unit) {
    val context = LocalContext.current
    val rgGateway = remember(context) { SettingsReplayGainGateway.shared(context) }
    val visualPreferencesRepo: org.michimusic.mobile.ui.theme.VisualPreferencesRepository = koinInject()
    val visualPrefs by visualPreferencesRepo.preferences.collectAsStateWithLifecycle()
    val appDao: AppDao = koinInject()
    val autoSyncScheduler: org.michimusic.mobile.sync.AutoSyncScheduler = koinInject()
    var selectedMode by remember { mutableStateOf(ReplayGainMode.OFF) }
    var replayGainLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(rgGateway) {
        rgGateway.observe().collect { snapshot ->
            selectedMode = snapshot.mode
            replayGainLoaded = true
        }
    }
    var listeningHistory by remember { mutableStateOf(true) }
    var historyLoaded by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var autoSync by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(appDao, autoSyncScheduler) {
        try {
            listeningHistory = appDao.getSetting("listening_history_enabled") != "false"
            historyLoaded = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { historyError = "No disponible" }
        try { autoSync = withContext(Dispatchers.IO) { autoSyncScheduler.isEnabled() } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { autoSync = null }
    }
                        SettingsHubIndex(
                            replayGainSummary = if (!replayGainLoaded) "Consultando…" else when (selectedMode) {
                                ReplayGainMode.OFF -> "Desactivado"
                                ReplayGainMode.TRACK -> "Pista"
                                ReplayGainMode.ALBUM -> "Álbum"
                                ReplayGainMode.DYNAMIC -> "Dinámico"
                            },
                            appearanceSummary = visualPrefs.appearanceProfile.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            historySummary = if (historyError != null) "Estado del historial no disponible" else
                                if (!historyLoaded) "Consultando historial" else if (listeningHistory)
                                "Historial local activado" else "Historial desactivado",
                            syncSummary = when (autoSync) {
                                true -> "Preferencia de sincronización activada"
                                false -> "Sincronización automática desactivada"
                                null -> "Consultando configuración"
                            },
                            onOpen = onOpen,
                        )
}
