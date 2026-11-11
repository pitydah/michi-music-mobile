package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.data.cache.AppDao
import org.michimusic.mobile.ui.theme.PrimaryPink






/** Historical records are NEVER cleared by switching off future history collection. */
@Composable
internal fun SettingsPrivacyPanel() {
    val appDao: AppDao = koinInject()
    val scope = rememberCoroutineScope()
    var historyPending by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var listeningHistory by remember { mutableStateOf(true) }
    var historyLoaded by remember { mutableStateOf(false) }
    var readRetry by remember { mutableStateOf(0) }
    LaunchedEffect(appDao, readRetry) {
        historyError = null
        try {
            listeningHistory = appDao.getSetting("listening_history_enabled") != "false"
            historyLoaded = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            historyError = "No se pudo leer la preferencia del historial."
            historyLoaded = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionCard(
            icon = Icons.Default.Security,
            title = "Privacidad",
            subtitle = "Historial local y datos del dispositivo",
            accent = PrimaryPink,
        ) {
            SettingSwitchRow(
                title = "Guardar historial de reproducción",
                subtitle = "Solo registros futuros. Desactivar no borra el historial anterior.",
                checked = listeningHistory,
                enabled = historyLoaded && !historyPending,
                onCheckedChange = { enabled ->
                    historyPending = true
                    historyError = null
                    scope.launch {
                        try {
                            appDao.setSetting(
                                org.michimusic.data.cache.SettingsEntity(
                                    key = "listening_history_enabled",
                                    value = enabled.toString(),
                                ),
                            )
                            listeningHistory = enabled
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            historyError = "No se guardó el cambio. Inténtalo nuevamente."
                        } finally { historyPending = false }
                    }
                },
                testTag = "settings_listening_history",
            )
            historyError?.let { message ->
                Text(message, color = PrimaryPink)
                androidx.compose.material3.TextButton(
                    onClick = { readRetry++ },
                    modifier = Modifier.testTag("settings_history_retry"),
                ) { Text("Reintentar lectura") }
            }



        }
    }
}
