package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.SettingsEntity
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.PremiumScreen
import org.michimusic.mobile.ui.screens.settings.SettingsButton
import org.michimusic.mobile.ui.screens.settings.SettingsDangerButton
import org.michimusic.mobile.ui.screens.settings.SettingsRadioGroup
import org.michimusic.mobile.ui.screens.settings.SettingsSection
import org.michimusic.mobile.ui.screens.settings.SettingsSlider
import org.michimusic.mobile.ui.screens.settings.SettingsTextField
import org.michimusic.mobile.ui.screens.settings.SettingsToggle
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.ReplayGainMode
import org.michimusic.player.ReplayGainPreAmp

private const val SETTINGS_PREFS = "michi_settings"
private const val KEY_AUTO_SYNC = "auto_sync"
private const val KEY_RG_MODE = "replaygain_mode"
private const val KEY_RG_PREAMP_WITH = "replaygain_preamp_with"
private const val KEY_RG_PREAMP_WITHOUT = "replaygain_preamp_without"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_DYNAMIC_ACCENTS = "dynamic_accents"
private const val KEY_GLASS_TEXTURES = "glass_textures"
private const val KEY_COMPACT_NOW_PLAYING = "compact_now_playing"
private const val KEY_WIFI_ONLY_SYNC = "wifi_only_sync"
private const val KEY_DOWNLOAD_ARTWORK = "download_artwork"
private const val KEY_AUTO_SCAN_LIBRARY = "auto_scan_library"
private const val KEY_DEFAULT_SLEEP_TIMER = "default_sleep_timer"
private const val DB_LISTENING_HISTORY_ENABLED = "listening_history_enabled"

@Composable
fun SettingsScreen(
    onNavigateToDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    val appDao: AppDao = koinInject()
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    var autoSync by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_SYNC, false)) }
    var wifiOnlySync by remember { mutableStateOf(prefs.getBoolean(KEY_WIFI_ONLY_SYNC, true)) }
    var downloadArtwork by remember { mutableStateOf(prefs.getBoolean(KEY_DOWNLOAD_ARTWORK, true)) }
    var autoScanLibrary by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_SCAN_LIBRARY, true)) }
    var dynamicAccents by remember { mutableStateOf(prefs.getBoolean(KEY_DYNAMIC_ACCENTS, true)) }
    var glassTextures by remember { mutableStateOf(prefs.getBoolean(KEY_GLASS_TEXTURES, true)) }
    var compactNowPlaying by remember { mutableStateOf(prefs.getBoolean(KEY_COMPACT_NOW_PLAYING, false)) }
    var listeningHistory by remember { mutableStateOf(true) }
    var serverUrl by remember { mutableStateOf(prefs.getString(KEY_SERVER_URL, "") ?: "") }
    val defaultSleepTimer = remember { mutableFloatStateOf(prefs.getFloat(KEY_DEFAULT_SLEEP_TIMER, 0f)) }
    var selectedMode by remember {
        mutableStateOf(
            runCatching {
                ReplayGainMode.valueOf(
                    prefs.getString(KEY_RG_MODE, ReplayGainMode.OFF.name) ?: ReplayGainMode.OFF.name,
                )
            }.getOrDefault(ReplayGainMode.OFF),
        )
    }
    val preAmpWith = remember { mutableFloatStateOf(prefs.getFloat(KEY_RG_PREAMP_WITH, 0f)) }
    val preAmpWithout = remember { mutableFloatStateOf(prefs.getFloat(KEY_RG_PREAMP_WITHOUT, 0f)) }

    LaunchedEffect(Unit) {
        listeningHistory = appDao.getSetting(DB_LISTENING_HISTORY_ENABLED) != "false"
    }

    PremiumScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            SettingsSection(title = "Ajustes")
            Text("Conexión, sincronización y reproducción", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Spacer(Modifier.height(16.dp))

            // --- Server ---
            SettingsSection(title = "Servidor")
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("La detección automática por UDP busca Michi Music Player en tu red local.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; prefs.edit { putString(KEY_SERVER_URL, it) } },
                    label = { Text("URL manual") },
                    placeholder = { Text("http://192.168.1.100:53318") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPink, unfocusedBorderColor = SurfaceElevated,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentPink,
                    ),
                )
            }

            // --- Sync ---
            SettingsSection(title = "Sincronización")
            SettingsToggle("Automática", "Sincronizar al conectar", autoSync) {
                autoSync = it; prefs.edit { putBoolean(KEY_AUTO_SYNC, it) }
            }
            SettingsToggle("Solo con Wi-Fi", "Evita descargas grandes con datos móviles", wifiOnlySync) {
                wifiOnlySync = it; prefs.edit { putBoolean(KEY_WIFI_ONLY_SYNC, it) }
            }
            SettingsToggle("Descargar carátulas", "Guardar artwork para visualización offline", downloadArtwork) {
                downloadArtwork = it; prefs.edit { putBoolean(KEY_DOWNLOAD_ARTWORK, it) }
            }
            SettingsToggle("Escaneo automático", "Detectar cambios en la biblioteca", autoScanLibrary) {
                autoScanLibrary = it; prefs.edit { putBoolean(KEY_AUTO_SCAN_LIBRARY, it) }
            }

            // --- Playback ---
            SettingsSection(title = "Reproducción")
            SettingsRadioGroup(
                title = "Modo ReplayGain",
                options = listOf(
                    ReplayGainMode.OFF.name to "Desactivado",
                    ReplayGainMode.TRACK.name to "Pista individual",
                    ReplayGainMode.ALBUM.name to "Álbum completo",
                    ReplayGainMode.DYNAMIC.name to "Dinámico",
                ),
                selectedValue = selectedMode.name,
                onSelect = {
                    selectedMode = ReplayGainMode.valueOf(it)
                    prefs.edit { putString(KEY_RG_MODE, it) }
                },
            )
            SettingsSlider("Pre-amp (con metadatos)", preAmpWith.floatValue, { preAmpWith.floatValue = it; prefs.edit { putFloat(KEY_RG_PREAMP_WITH, it) } }, "%.1f".format(preAmpWith.floatValue))
            SettingsSlider("Pre-amp (sin metadatos)", preAmpWithout.floatValue, { preAmpWithout.floatValue = it; prefs.edit { putFloat(KEY_RG_PREAMP_WITHOUT, it) } }, "%.1f".format(preAmpWithout.floatValue))

            SettingsToggle("Acentos dinámicos", "Color de acento basado en la carátula", dynamicAccents) {
                dynamicAccents = it; prefs.edit { putBoolean(KEY_DYNAMIC_ACCENTS, it) }
            }
            SettingsToggle("Texturas de vidrio", "Efectos glassmorphism premium", glassTextures) {
                glassTextures = it; prefs.edit { putBoolean(KEY_GLASS_TEXTURES, it) }
            }
            SettingsToggle("Now Playing compacto", "Oculta botones secundarios", compactNowPlaying) {
                compactNowPlaying = it; prefs.edit { putBoolean(KEY_COMPACT_NOW_PLAYING, it) }
            }
            SettingsSlider("Temporizador de sueño (min)", defaultSleepTimer.floatValue / 60_000f, { defaultSleepTimer.floatValue = (it * 60_000f).toLong().toFloat(); prefs.edit { putFloat(KEY_DEFAULT_SLEEP_TIMER, defaultSleepTimer.floatValue) } }, "${(defaultSleepTimer.floatValue / 60_000f).toInt()}", valueRange = 0f..120f, steps = 119)

            // --- Data ---
            SettingsSection(title = "Datos e historial")
            SettingsToggle("Historial de escucha", "Registra las canciones que reproduces", listeningHistory) {
                listeningHistory = it
                scope.launch { appDao.setSetting(SettingsEntity(DB_LISTENING_HISTORY_ENABLED, if (it) "true" else "false")) }
            }
            SettingsDangerButton(text = "Borrar historial") {
                scope.launch { appDao.clearHistory() }
            }
            SettingsDangerButton(text = "Limpiar conteos") {
                scope.launch { appDao.clearPlayCounts() }
            }

            // --- Diagnostics ---
            SettingsSection(title = "Diagnóstico")
            SettingsButton(text = "Ejecutar diagnóstico", onClick = onNavigateToDiagnostics)

            Spacer(Modifier.height(32.dp))
        }
    }
}
