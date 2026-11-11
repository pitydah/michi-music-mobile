package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.TextSecondary


import org.michimusic.mobile.ui.theme.SecondaryPurple




/** Scheduling is an explicit preference, not evidence of a running job or connected peer. */
@Composable
internal fun SettingsEcosystemPanel(onNavigateToDevices: () -> Unit) {
    val scope = rememberCoroutineScope()
    val autoSyncScheduler: org.michimusic.mobile.sync.AutoSyncScheduler = koinInject()
    val identityAuthority: org.michimusic.link.identity.MichiIdentityAuthority = koinInject()
    val identityState by identityAuthority.state.collectAsStateWithLifecycle()
    val scheduleEvents = remember(autoSyncScheduler) { autoSyncScheduler.observeWorkTruth() }
    val observedSchedule by scheduleEvents.collectAsStateWithLifecycle(
        initialValue = org.michimusic.mobile.sync.AutoSyncWorkTruth.UNKNOWN,
    )
    var autoSync by remember { mutableStateOf<Boolean?>(null) }
    var autoSyncError by remember { mutableStateOf<String?>(null) }
    var autoSyncPending by remember { mutableStateOf(false) }
    LaunchedEffect(autoSyncScheduler) {
        try { autoSync = withContext(Dispatchers.IO) { autoSyncScheduler.isEnabled() } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { autoSyncError = "No se pudo consultar la sincronización automática." }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionCard(
            icon = Icons.Default.Security,
            title = "Sincronización y dispositivos",
            subtitle = "Preferencias locales; el estado del servidor se consulta en Dispositivos",
            accent = SecondaryPurple,
        ) {
            SettingSwitchRow(
                title = "Sincronización automática",
                subtitle = "Preferencia local: revisión periódica cada 12 horas cuando hay pares y red.",
                checked = autoSync == true,
                enabled = autoSync != null && !autoSyncPending,
                onCheckedChange = { enabled ->
                    autoSyncPending = true
                    autoSyncError = null
                    scope.launch {
                        try {
                            autoSyncScheduler.setEnabled(enabled)
                            autoSync = withContext(Dispatchers.IO) { autoSyncScheduler.isEnabled() }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            autoSyncError = "No se pudo programar la sincronización."
                            autoSync = withContext(Dispatchers.IO) { autoSyncScheduler.isEnabled() }
                        } finally { autoSyncPending = false }
                    }
                },
                testTag = "settings_auto_sync",
            )
            Text(
                org.michimusic.mobile.sync.autoSyncWorkStatusLabel(autoSync, observedSchedule),
                color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings_auto_sync_work_status"),
            )
            Text("Programación observada en Android; no confirma conexión ni descarga efectiva.",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            autoSyncError?.let { Text(it, color = PrimaryPink) }
        }
        SettingsCategoryNavCard(
            icon = Icons.Default.FolderSpecial,
            iconRes = org.michimusic.mobile.R.drawable.wizard_fluent_phone_laptop,
            title = "Michi Link, Micro Server y Music Stream",
            subtitle = "Administrar dispositivos, conexiones y receptores desde su pantalla canónica",
            accent = SecondaryPurple,
            onClick = onNavigateToDevices,
            testTag = "settings_open_devices",
        )
        IdentityStatusRow(
            state = identityState,
            onRetry = { identityAuthority.retry(scope) },
        )
    }
}
