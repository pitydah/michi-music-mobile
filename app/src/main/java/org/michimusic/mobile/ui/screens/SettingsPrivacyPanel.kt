package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiSharingConsentPolicy
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.SettingsEntity
import org.michimusic.data.intelligence.MichiIntelligencePrivacyPreferences
import org.michimusic.data.intelligence.MichiIntelligenceRepository
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * MI-05: user-owned privacy controls in the existing Aero Settings section.
 * No automatic learning, provider connection, scan, polling or recurring work.
 */
@Composable
internal fun SettingsPrivacyPanel() {
    val appDao: AppDao = koinInject()
    val context = LocalContext.current.applicationContext
    val repo = remember(context, appDao) { MichiIntelligenceRepository(context, appDao) }
    val privacy = remember(context) { MichiIntelligencePrivacyPreferences(context) }
    val scope = rememberCoroutineScope()

    var historyPending by remember { mutableStateOf(false) }
    var learningPending by remember { mutableStateOf(false) }
    var modePending by remember { mutableStateOf(false) }
    var pendingEraseDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var listeningHistory by remember { mutableStateOf(false) }
    var learningEnabled by remember { mutableStateOf(false) }
    var privacyMode by remember {
        mutableStateOf(MichiSharingConsentPolicy.PrivacyMode.MAXIMUM)
    }
    var loaded by remember { mutableStateOf(false) }
    var readRetry by remember { mutableStateOf(0) }

    LaunchedEffect(appDao, repo, privacy, readRetry) {
        loaded = false
        error = null
        try {
            listeningHistory = appDao.getSetting("listening_history_enabled") != "false"
            learningEnabled = repo.isEnabled()
            privacyMode = privacy.readMode()
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "No se pudo comprobar la privacidad. Todos los cambios están bloqueados."
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
                subtitle = "Solo registros futuros. Desactivar no borra el historial anterior, pero elimina las preferencias de Intelligence.",
                checked = listeningHistory,
                enabled = loaded && !historyPending && !learningPending,
                onCheckedChange = { enabled ->
                    val durableErase = if (!enabled) repo.requestEraseFromUserAction() else null
                    if (!enabled) {
                        learningEnabled = false // immediate UI fail-closed; disk cleanup survives navigation
                    }
                    historyPending = true
                    notice = null
                    error = null
                    scope.launch {
                        try {
                            // Persist history OFF before attempting purge; fail closed on failure.
                            appDao.setSetting(SettingsEntity("listening_history_enabled", enabled.toString()))
                            listeningHistory = enabled
                            if (!enabled) {
                                try {
                                    durableErase!!.await()
                                    learningEnabled = false
                                    notice = "Aprendizaje desactivado y preferencias locales eliminadas."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    learningEnabled = false
                                    error = "Historial desactivado. No se pudo confirmar el borrado: vuelve a intentarlo."
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "No se guardó el cambio de historial. El aprendizaje permanece bloqueado."
                        } finally {
                            historyPending = false
                        }
                    }
                },
                testTag = "settings_listening_history",
            )
        }

        SettingsSectionCard(
            icon = Icons.Default.Security,
            title = "Michi Intelligence",
            subtitle = "Recomendaciones privadas para Home y Géneros",
            accent = PrimaryPink,
        ) {
            SettingSwitchRow(
                title = "Aprender de mi escucha",
                subtitle = "Opcional y solo en este teléfono. Al desactivarlo se eliminan las preferencias aprendidas.",
                checked = learningEnabled && listeningHistory,
                enabled = loaded && listeningHistory && !historyPending && !learningPending,
                onCheckedChange = { enabled ->
                    val durableErase = if (!enabled) repo.requestEraseFromUserAction() else null
                    if (!enabled) learningEnabled = false
                    learningPending = true
                    error = null
                    notice = null
                    scope.launch {
                        try {
                            if (enabled) {
                                if (repo.enableFromExplicitConsent()) {
                                    learningEnabled = true
                                    notice = "Aprendizaje local activado. No se enviará tu biblioteca."
                                } else {
                                    learningEnabled = false
                                    error = "Activa primero el historial local para aprender de tu escucha."
                                }
                            } else {
                                durableErase!!.await()
                                learningEnabled = false
                                notice = "Preferencias de Intelligence eliminadas de este teléfono."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            learningEnabled = false
                            error = "No se pudo confirmar el cambio. El aprendizaje permanece bloqueado."
                        } finally {
                            learningPending = false
                        }
                    }
                },
                testTag = "settings_intelligence_learning",
            )
            if (!listeningHistory) {
                Text("El historial local está desactivado. Intelligence no registra escuchas.")
            }
            TextButton(
                enabled = loaded && !historyPending && !learningPending,
                onClick = { pendingEraseDialog = true },
                modifier = Modifier.testTag("settings_intelligence_erase"),
            ) { Text("Borrar preferencias aprendidas") }
        }

        SettingsSectionCard(
            icon = Icons.Default.Security,
            title = "Privacidad de Intelligence",
            subtitle = "Preferencia local para solicitudes futuras, nunca un permiso automático",
            accent = PrimaryPink,
        ) {
            val options = listOf(
                Triple(MichiSharingConsentPolicy.PrivacyMode.MAXIMUM,
                    "Máxima privacidad", "Solo en este teléfono"),
                Triple(MichiSharingConsentPolicy.PrivacyMode.LIMITED_SHARING,
                    "Compartición limitada", "Información pública; permiso por tarea"),
                Triple(MichiSharingConsentPolicy.PrivacyMode.CUSTOM_SHARING,
                    "Compartición personalizada", "Elige y revisa antes de enviar"),
            )
            options.forEach { (mode, title, subtitle) ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (privacyMode == mode) PrimaryPink.copy(alpha = 0.10f) else Color.Transparent)
                        .testTag("settings_intelligence_privacy_${mode.name.lowercase()}")
                        .selectable(
                            selected = privacyMode == mode,
                            enabled = loaded && !modePending,
                            role = Role.RadioButton,
                            onClick = {
                                modePending = true
                                error = null
                                notice = null
                                scope.launch {
                                    try {
                                        privacy.setMode(mode)
                                        privacyMode = mode
                                        notice = "Preferencia local guardada. No se ha compartido ningún dato."
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        error = "No se pudo guardar el modo de privacidad."
                                    } finally {
                                        modePending = false
                                    }
                                }
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = privacyMode == mode, onClick = null,
                        enabled = loaded && !modePending)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
            Text(
                "Gemini no está conectado. Ninguna opción autoriza envíos.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.testTag("settings_intelligence_provider_off"),
            )
            SettingsDisclosureNote(
                title = "Qué permite cada nivel de privacidad",
                detail = "Máxima: todo local, sin enviar datos a servicios externos. " +
                    "Limitada: solo información pública o géneros elegidos, siempre con aprobación por tarea. " +
                    "Personalizada: podrás elegir canciones o playlists concretas y revisar " +
                    "los datos antes de enviarlos. Cada solicitud futura exige una aprobación explícita.",
                testTag = "settings_intelligence_sharing_help",
            )
        }

        if (error != null) {
            Text(error!!, color = PrimaryPink, modifier = Modifier.testTag("settings_privacy_error"))
            TextButton(onClick = { readRetry++ }, modifier = Modifier.testTag("settings_history_retry")) {
                Text("Reintentar comprobación")
            }
        }
        if (notice != null) {
            Text(notice!!, modifier = Modifier.testTag("settings_intelligence_notice"))
        }
    }

    if (pendingEraseDialog) {
        AlertDialog(
            onDismissRequest = { pendingEraseDialog = false },
            title = { Text("¿Borrar preferencias de Intelligence?") },
            text = { Text("Se eliminará el perfil aprendido del dispositivo. Tus canciones, playlists y el historial anterior no se borrarán.") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("settings_intelligence_erase_confirm"),
                    onClick = {
                        val durableErase = repo.requestEraseFromUserAction()
                        learningEnabled = false
                        pendingEraseDialog = false
                        learningPending = true
                        error = null
                        notice = null
                        scope.launch {
                            try {
                                durableErase.await()
                                learningEnabled = false
                                notice = "Preferencias aprendidas eliminadas del dispositivo."
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                learningEnabled = false
                                error = "No se pudo confirmar el borrado. Vuelve a intentarlo."
                            } finally {
                                learningPending = false
                            }
                        }
                    },
                ) { Text("Borrar preferencias") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEraseDialog = false }) { Text("Cancelar") }
            },
        )
    }
}
