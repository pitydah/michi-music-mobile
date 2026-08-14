package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.data.cache.AppDao
import org.michimusic.mobile.ui.components.AudioRouteDialog
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.ReplayGainMode

private const val SETTINGS_PREFS = "michi_settings"
private const val KEY_RG_MODE = "replaygain_mode"
private const val KEY_RG_PREAMP_WITH = "replaygain_preamp_with"
private const val KEY_RG_PREAMP_WITHOUT = "replaygain_preamp_without"
private const val KEY_DOWNLOAD_ARTWORK = "download_artwork"
private const val KEY_AUTO_SCAN_LIBRARY = "auto_scan_library"
private const val DB_LISTENING_HISTORY_ENABLED = "listening_history_enabled"

@Composable
fun SettingsScreen(
    onNavigateToDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    val appDao: AppDao = koinInject()
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }

    var downloadArtwork by remember { mutableStateOf(prefs.getBoolean(KEY_DOWNLOAD_ARTWORK, true)) }
    var autoScanLibrary by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_SCAN_LIBRARY, true)) }
    var listeningHistory by remember { mutableStateOf(true) }

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
    var showAudioRouteDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        listeningHistory = appDao.getSetting(DB_LISTENING_HISTORY_ENABLED) != "false"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .drawBehind {
                drawCircle(
                    color = PrimaryPinkContainer.copy(alpha = 0.08f),
                    radius = size.width * 0.7f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.1f),
                )
                drawCircle(
                    color = TertiaryCyan.copy(alpha = 0.05f),
                    radius = size.width * 0.6f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.85f),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "AJUSTES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TertiaryCyan,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Preferencias & Audio",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                            letterSpacing = (-0.5).sp,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 1. Audio & Salidas
            SettingsSectionCard(
                icon = Icons.Default.GraphicEq,
                title = "Audio & Procesamiento",
                subtitle = "Ecualización, Salidas y ReplayGain",
                accent = TertiaryCyan,
            ) {
                // Audio Route & Equalizer Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        backgroundColor = GlassFillLow,
                        borderColor = GlassBorderLow,
                        onClick = { showEqualizerDialog = true },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = PrimaryPink, modifier = Modifier.size(22.dp))
                            Column {
                                Text("Ecualizador", color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("5 Bandas & 3D", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }

                    GlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        backgroundColor = GlassFillLow,
                        borderColor = GlassBorderLow,
                        onClick = { showAudioRouteDialog = true },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Default.Headphones, contentDescription = null, tint = TertiaryCyan, modifier = Modifier.size(22.dp))
                            Column {
                                Text("Salidas de Audio", color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("DAC USB & Enrutamiento", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Modo de Normalización ReplayGain",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )

                Spacer(Modifier.height(10.dp))

                // ReplayGain 2x2 Mode Selection
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(ReplayGainMode.OFF, ReplayGainMode.TRACK).forEach { mode ->
                            val isSelected = selectedMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) TertiaryCyan.copy(alpha = 0.20f) else GlassFillLow)
                                    .border(1.dp, if (isSelected) TertiaryCyan else GlassBorderLow, RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedMode = mode
                                        prefs.edit { putString(KEY_RG_MODE, mode.name) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = when (mode) {
                                        ReplayGainMode.OFF -> "Desactivado"
                                        ReplayGainMode.TRACK -> "Pista"
                                        else -> mode.name
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) TertiaryCyan else TextSecondary,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(ReplayGainMode.ALBUM, ReplayGainMode.DYNAMIC).forEach { mode ->
                            val isSelected = selectedMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) TertiaryCyan.copy(alpha = 0.20f) else GlassFillLow)
                                    .border(1.dp, if (isSelected) TertiaryCyan else GlassBorderLow, RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedMode = mode
                                        prefs.edit { putString(KEY_RG_MODE, mode.name) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = when (mode) {
                                        ReplayGainMode.ALBUM -> "Álbum"
                                        ReplayGainMode.DYNAMIC -> "Dinámico"
                                        else -> mode.name
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) TertiaryCyan else TextSecondary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. Audio Avanzado (Preamp)
            SettingsSectionCard(
                icon = Icons.Default.Tune,
                title = "Pre-amplificación ReplayGain",
                subtitle = "Ajuste de decibelios con y sin tags RG",
                accent = PrimaryPink,
            ) {
                // Preamp with Tags
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Pre-amp con tags RG", fontSize = 13.sp, color = TextPrimary)
                        Text(String.format("%.1f dB", preAmpWith.floatValue), fontSize = 13.sp, color = TertiaryCyan, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    org.michimusic.mobile.ui.components.MichiFineSlider(
                        value = preAmpWith.floatValue,
                        onValueChange = {
                            preAmpWith.floatValue = it
                            prefs.edit { putFloat(KEY_RG_PREAMP_WITH, it) }
                        },
                        valueRange = -15f..15f,
                        steps = 29,
                        accentColor = TertiaryCyan,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Preamp without Tags
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Pre-amp sin tags RG", fontSize = 13.sp, color = TextPrimary)
                        Text(String.format("%.1f dB", preAmpWithout.floatValue), fontSize = 13.sp, color = PrimaryPink, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    org.michimusic.mobile.ui.components.MichiFineSlider(
                        value = preAmpWithout.floatValue,
                        onValueChange = {
                            preAmpWithout.floatValue = it
                            prefs.edit { putFloat(KEY_RG_PREAMP_WITHOUT, it) }
                        },
                        valueRange = -15f..15f,
                        steps = 29,
                        accentColor = PrimaryPink,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 3. Biblioteca & Almacenamiento
            SettingsSectionCard(
                icon = Icons.Default.FolderSpecial,
                title = "Biblioteca & Almacenamiento",
                subtitle = "Escaneo y carátulas",
                accent = TertiaryCyan,
            ) {
                SettingSwitchRow(
                    title = "Escanear al iniciar",
                    subtitle = "Detectar nueva música automáticamente al abrir la app",
                    checked = autoScanLibrary,
                    onCheckedChange = {
                        autoScanLibrary = it
                        prefs.edit { putBoolean(KEY_AUTO_SCAN_LIBRARY, it) }
                    },
                )

                Spacer(Modifier.height(12.dp))

                SettingSwitchRow(
                    title = "Descargar carátulas",
                    subtitle = "Obtener portadas en alta resolución si faltan",
                    checked = downloadArtwork,
                    onCheckedChange = {
                        downloadArtwork = it
                        prefs.edit { putBoolean(KEY_DOWNLOAD_ARTWORK, it) }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // 4. Privacidad
            SettingsSectionCard(
                icon = Icons.Default.Security,
                title = "Privacidad",
                subtitle = "Historial local y datos del dispositivo",
                accent = PrimaryPink,
            ) {
                SettingSwitchRow(
                    title = "Historial de reproducción",
                    subtitle = "Registrar canciones reproducidas para listas automáticas",
                    checked = listeningHistory,
                    onCheckedChange = { isChecked ->
                        listeningHistory = isChecked
                        scope.launch {
                            appDao.setSetting(
                                org.michimusic.data.cache.SettingsEntity(
                                    key = DB_LISTENING_HISTORY_ENABLED,
                                    value = if (isChecked) "true" else "false",
                                ),
                            )
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // 5. Diagnóstico & Sistema
            SettingsSectionCard(
                icon = Icons.Default.Info,
                title = "Sistema & Diagnóstico",
                subtitle = "Herramientas técnicas y estado del sistema",
                accent = TertiaryCyan,
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = GlassFillLow,
                    borderColor = GlassBorderLow,
                    onClick = onNavigateToDiagnostics,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = TertiaryCyan, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Diagnóstico del Sistema", color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Verificar Media3, Koin, Room y Audio HAL", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(100.dp))
        }

        if (showEqualizerDialog) {
            EqualizerDialog(onDismiss = { showEqualizerDialog = false })
        }

        if (showAudioRouteDialog) {
            AudioRouteDialog(onDismiss = { showAudioRouteDialog = false })
        }
    }
}

@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = SurfaceObsidian.copy(alpha = 0.7f),
        borderColor = GlassBorderLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(title, color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = TextSecondary, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PureWhite,
                checkedTrackColor = TertiaryCyan,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = GlassFillHigh,
            ),
        )
    }
}
