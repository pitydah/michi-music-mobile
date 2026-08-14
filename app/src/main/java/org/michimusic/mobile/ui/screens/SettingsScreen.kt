package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.SettingsEntity
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.ReplayGainMode

private const val SETTINGS_PREFS = "michi_settings"
private const val KEY_AUTO_SYNC = "auto_sync"
private const val KEY_RG_MODE = "replaygain_mode"
private const val KEY_RG_PREAMP_WITH = "replaygain_preamp_with"
private const val KEY_RG_PREAMP_WITHOUT = "replaygain_preamp_without"
private const val KEY_DYNAMIC_ACCENTS = "dynamic_accents"
private const val KEY_GLASS_TEXTURES = "glass_textures"
private const val KEY_COMPACT_NOW_PLAYING = "compact_now_playing"
private const val KEY_WIFI_ONLY_SYNC = "wifi_only_sync"
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

    var wifiOnlySync by remember { mutableStateOf(prefs.getBoolean(KEY_WIFI_ONLY_SYNC, true)) }
    var downloadArtwork by remember { mutableStateOf(prefs.getBoolean(KEY_DOWNLOAD_ARTWORK, true)) }
    var autoScanLibrary by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_SCAN_LIBRARY, true)) }
    var dynamicAccents by remember { mutableStateOf(prefs.getBoolean(KEY_DYNAMIC_ACCENTS, true)) }
    var glassTextures by remember { mutableStateOf(prefs.getBoolean(KEY_GLASS_TEXTURES, true)) }
    var compactNowPlaying by remember { mutableStateOf(prefs.getBoolean(KEY_COMPACT_NOW_PLAYING, false)) }
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

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(GlassFillHigh)
                        .border(1.dp, GlassBorderHigh, CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = PrimaryPink),
                            onClick = onNavigateToDiagnostics,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = "Diagnóstico",
                        tint = TertiaryCyan,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 1. Audio Engine & ReplayGain Section
            SettingsSectionCard(
                icon = Icons.Default.GraphicEq,
                title = "Motor de Audio & ReplayGain",
                subtitle = "Normalización de volumen y fidelidad",
                accent = TertiaryCyan,
            ) {
                Text(
                    text = "Modo de Normalización ReplayGain",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )

                Spacer(Modifier.height(10.dp))

                // 2x2 Grid for ReplayGain Modes to prevent crowding
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
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) TertiaryCyan.copy(alpha = 0.20f) else GlassFillLow)
                                    .border(
                                        1.dp,
                                        if (isSelected) TertiaryCyan else GlassBorderLow,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable {
                                        selectedMode = mode
                                        prefs.edit { putString(KEY_RG_MODE, mode.name) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = mode.name,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
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
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) TertiaryCyan.copy(alpha = 0.20f) else GlassFillLow)
                                    .border(
                                        1.dp,
                                        if (isSelected) TertiaryCyan else GlassBorderLow,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable {
                                        selectedMode = mode
                                        prefs.edit { putString(KEY_RG_MODE, mode.name) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = mode.name,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) TertiaryCyan else TextSecondary,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Pre-amp with tags
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Pre-Amplificación con tags",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                    Text(
                        text = "${preAmpWith.floatValue.toInt()} dB",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryPink,
                    )
                }

                Slider(
                    value = preAmpWith.floatValue,
                    onValueChange = {
                        preAmpWith.floatValue = it
                        prefs.edit { putFloat(KEY_RG_PREAMP_WITH, it) }
                    },
                    valueRange = -15f..15f,
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryPink,
                        activeTrackColor = PrimaryPink,
                        inactiveTrackColor = GlassBorderLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))

                // Pre-amp without tags
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Pre-Amplificación sin tags",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                    Text(
                        text = "${preAmpWithout.floatValue.toInt()} dB",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TertiaryCyan,
                    )
                }

                Slider(
                    value = preAmpWithout.floatValue,
                    onValueChange = {
                        preAmpWithout.floatValue = it
                        prefs.edit { putFloat(KEY_RG_PREAMP_WITHOUT, it) }
                    },
                    valueRange = -15f..15f,
                    colors = SliderDefaults.colors(
                        thumbColor = TertiaryCyan,
                        activeTrackColor = TertiaryCyan,
                        inactiveTrackColor = GlassBorderLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))

            // 2. Visual & Glass Experience
            SettingsSectionCard(
                icon = Icons.Default.Palette,
                title = "Experiencia Digital Ethereal",
                subtitle = "Efectos visuales y texturas glassmórficas",
                accent = PrimaryPink,
            ) {
                SettingsSwitchRow(
                    title = "Acentos Dinámicos de Color",
                    subtitle = "Extraer tonalidades vibrantes de las carátulas",
                    checked = dynamicAccents,
                    onCheckedChange = {
                        dynamicAccents = it
                        prefs.edit { putBoolean(KEY_DYNAMIC_ACCENTS, it) }
                    },
                )

                Spacer(Modifier.height(14.dp))

                SettingsSwitchRow(
                    title = "Texturas Glassmorphic Avanzadas",
                    subtitle = "Reflejos especulares y transparencias",
                    checked = glassTextures,
                    onCheckedChange = {
                        glassTextures = it
                        prefs.edit { putBoolean(KEY_GLASS_TEXTURES, it) }
                    },
                )

                Spacer(Modifier.height(14.dp))

                SettingsSwitchRow(
                    title = "Modo Compacto en Now Playing",
                    subtitle = "Minimizar espaciado para pantallas pequeñas",
                    checked = compactNowPlaying,
                    onCheckedChange = {
                        compactNowPlaying = it
                        prefs.edit { putBoolean(KEY_COMPACT_NOW_PLAYING, it) }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // 3. Sincronización & Red
            SettingsSectionCard(
                icon = Icons.Default.Sync,
                title = "Michi Link & Sincronización",
                subtitle = "Conexión local y descarga de colecciones",
                accent = SecondaryPurple,
            ) {
                SettingsSwitchRow(
                    title = "Sincronizar Solo por Wi-Fi",
                    subtitle = "Proteger datos móviles durante descargas pesadas",
                    checked = wifiOnlySync,
                    onCheckedChange = {
                        wifiOnlySync = it
                        prefs.edit { putBoolean(KEY_WIFI_ONLY_SYNC, it) }
                    },
                )

                Spacer(Modifier.height(14.dp))

                SettingsSwitchRow(
                    title = "Descargar Carátulas en Alta Resolución",
                    subtitle = "Guardar arte de álbum localmente para modo offline",
                    checked = downloadArtwork,
                    onCheckedChange = {
                        downloadArtwork = it
                        prefs.edit { putBoolean(KEY_DOWNLOAD_ARTWORK, it) }
                    },
                )

                Spacer(Modifier.height(14.dp))

                SettingsSwitchRow(
                    title = "Auto-Escaneo de Biblioteca Local",
                    subtitle = "Detectar nuevos archivos de audio automáticamente",
                    checked = autoScanLibrary,
                    onCheckedChange = {
                        autoScanLibrary = it
                        prefs.edit { putBoolean(KEY_AUTO_SCAN_LIBRARY, it) }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // 4. Privacidad y Datos Locales
            SettingsSectionCard(
                icon = Icons.Default.Security,
                title = "Privacidad & Historial",
                subtitle = "Gestión de datos Room 100% locales",
                accent = TertiaryCyan,
            ) {
                SettingsSwitchRow(
                    title = "Historial de Reproducción",
                    subtitle = "Registrar canciones recientes para el inicio",
                    checked = listeningHistory,
                    onCheckedChange = { enabled ->
                        listeningHistory = enabled
                        scope.launch {
                            appDao.setSetting(
                                SettingsEntity(
                                    key = DB_LISTENING_HISTORY_ENABLED,
                                    value = enabled.toString(),
                                ),
                            )
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // 5. Diagnostics Card Action
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassFillHigh)
                    .border(1.dp, GlassBorderHigh, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = TertiaryCyan),
                        onClick = onNavigateToDiagnostics,
                    )
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(TertiaryCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = null,
                                tint = TertiaryCyan,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        Column {
                            Text(
                                text = "Panel de Diagnóstico",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Inspeccionar sockets, tokens y telemetría",
                                fontSize = 12.sp,
                                color = TextSecondary,
                            )
                        }
                    }

                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(120.dp))
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFillLow)
            .border(1.dp, GlassBorderLow, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = PureWhite,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = TextSecondary,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PureWhite,
                checkedTrackColor = PrimaryPinkContainer,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = GlassFillHigh,
                uncheckedBorderColor = GlassBorderLow,
            ),
        )
    }
}
