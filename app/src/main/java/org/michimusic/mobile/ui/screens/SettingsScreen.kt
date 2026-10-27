package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.edit
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.data.cache.AppDao
import org.michimusic.mobile.ui.components.AudioRouteDialog
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.shouldStackAudioActions
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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.SecondaryPurple

private const val SETTINGS_PREFS = "michi_settings"
private const val KEY_RG_MODE = "replaygain_mode"
private const val KEY_RG_PREAMP_WITH = "replaygain_preamp_with"
private const val KEY_RG_PREAMP_WITHOUT = "replaygain_preamp_without"
private const val KEY_DOWNLOAD_ARTWORK = "download_artwork"
private const val KEY_AUTO_SCAN_LIBRARY = "auto_scan_library"
private const val KEY_HIGH_CONTRAST = "high_contrast"
private const val KEY_REDUCED_MOTION = "reduced_motion"
private const val DB_LISTENING_HISTORY_ENABLED = "listening_history_enabled"

enum class SettingsSection {
    ROOT,
    AUDIO_QUALITY,
    LIBRARY_STORAGE,
    ACCESSIBILITY,
    PRIVACY,
}

@Composable
fun SettingsScreen(
    onNavigateToDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    val appDao: AppDao = koinInject()
    val visualPreferencesRepo: org.michimusic.mobile.ui.theme.VisualPreferencesRepository = koinInject()
    val visualPrefs by visualPreferencesRepo.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }

    var currentSection by rememberSaveable { mutableStateOf(SettingsSection.ROOT) }

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

    BackHandler(enabled = currentSection != SettingsSection.ROOT) {
        currentSection = SettingsSection.ROOT
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = MichiSpacing.screenHorizontal)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentSection != SettingsSection.ROOT) {
                        IconButton(
                            onClick = { currentSection = SettingsSection.ROOT },
                            modifier = Modifier
                                .size(MichiSpacing.minTouchTarget)
                                .testTag("settings_back_button"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Volver",
                                tint = PureWhite,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        Text(
                            text = "AJUSTES",
                            style = org.michimusic.mobile.ui.theme.MichiTypography.screenEyebrow,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when (currentSection) {
                                SettingsSection.ROOT -> "Preferencias & Audio"
                                SettingsSection.AUDIO_QUALITY -> "Calidad de Audio & DSP"
                                SettingsSection.LIBRARY_STORAGE -> "Biblioteca & Almacenamiento"
                                SettingsSection.ACCESSIBILITY -> "Accesibilidad & Visual"
                                SettingsSection.PRIVACY -> "Privacidad & Datos"
                            },
                            style = org.michimusic.mobile.ui.theme.MichiTypography.screenTitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = currentSection,
                transitionSpec = {
                    if (visualPrefs.reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        fadeIn() togetherWith fadeOut()
                    }
                },
                label = "settings_hierarchy",
            ) { target ->
                when (target) {
                    SettingsSection.ROOT -> {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            SettingsCategoryNavCard(
                                icon = Icons.Default.GraphicEq,
                                title = "Calidad de Audio & DSP",
                                subtitle = "ReplayGain, pre-amp, ecualizador y rutas DAC",
                                accent = TertiaryCyan,
                                onClick = { currentSection = SettingsSection.AUDIO_QUALITY },
                            )

                            SettingsCategoryNavCard(
                                icon = Icons.Default.FolderSpecial,
                                title = "Biblioteca & Almacenamiento",
                                subtitle = "Importación SAF, escaneo automático y carátulas",
                                accent = PrimaryPink,
                                onClick = { currentSection = SettingsSection.LIBRARY_STORAGE },
                            )

                            SettingsCategoryNavCard(
                                icon = Icons.Default.AccessibilityNew,
                                title = "Accesibilidad & Visual",
                                subtitle = "Contraste alto, animación reducida y objetivos táctiles",
                                accent = TertiaryCyan,
                                onClick = { currentSection = SettingsSection.ACCESSIBILITY },
                            )

                            SettingsCategoryNavCard(
                                icon = Icons.Default.Security,
                                title = "Privacidad",
                                subtitle = "Historial local y datos de reproducción",
                                accent = SecondaryPurple,
                                onClick = { currentSection = SettingsSection.PRIVACY },
                            )

                            SettingsCategoryNavCard(
                                icon = Icons.Default.BugReport,
                                title = "Diagnóstico del Sistema",
                                subtitle = "Auditoría de red, Media3 HAL y telemetría expurgada",
                                accent = TertiaryCyan,
                                onClick = onNavigateToDiagnostics,
                            )
                        }
                    }

                    SettingsSection.AUDIO_QUALITY -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SettingsSectionCard(
                                icon = Icons.Default.GraphicEq,
                                title = "Procesamiento de Audio",
                                subtitle = "Ecualización, Salidas y ReplayGain",
                                accent = TertiaryCyan,
                            ) {
                                // Adaptive DSP actions: stacking avoids squashing labels on narrow phones.
                                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                    SettingsAudioQuickActions(
                                        stacked = shouldStackAudioActions(
                                            maxWidth.value, LocalConfiguration.current.fontScale,
                                        ),
                                        onEqualizer = { showEqualizerDialog = true },
                                        onAudioRoute = { showAudioRouteDialog = true },
                                    )
                                }

                                Spacer(Modifier.height(16.dp))

                                Text(
                                    text = "Modo de Normalización ReplayGain",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                )

                                Spacer(Modifier.height(10.dp))

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
                                                    .height(48.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) TertiaryCyan.copy(alpha = 0.20f) else GlassFillLow)
                                                    .border(1.dp, if (isSelected) TertiaryCyan else GlassBorderLow, RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        selectedMode = mode
                                                        prefs.edit { putString(KEY_RG_MODE, mode.name) }
                                                    }
                                                    .semantics { selected = isSelected },
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
                                                    .height(48.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) TertiaryCyan.copy(alpha = 0.20f) else GlassFillLow)
                                                    .border(1.dp, if (isSelected) TertiaryCyan else GlassBorderLow, RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        selectedMode = mode
                                                        prefs.edit { putString(KEY_RG_MODE, mode.name) }
                                                        org.michimusic.player.PlayerDependencies.configureReplayGain(
                                                            mode,
                                                            org.michimusic.player.ReplayGainPreAmp(
                                                                with = preAmpWith.floatValue,
                                                                without = preAmpWithout.floatValue,
                                                            ),
                                                        )
                                                    }
                                                    .semantics { selected = isSelected },
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

                            SettingsSectionCard(
                                icon = Icons.Default.Tune,
                                title = "Pre-amplificación ReplayGain",
                                subtitle = "Ajuste de decibelios con y sin tags RG",
                                accent = PrimaryPink,
                            ) {
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
                                            org.michimusic.player.PlayerDependencies.configureReplayGain(
                                                selectedMode,
                                                org.michimusic.player.ReplayGainPreAmp(
                                                    with = it,
                                                    without = preAmpWithout.floatValue,
                                                ),
                                            )
                                        },
                                        valueRange = -15f..15f,
                                        steps = 29,
                                        accentColor = TertiaryCyan,
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

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
                                            org.michimusic.player.PlayerDependencies.configureReplayGain(
                                                selectedMode,
                                                org.michimusic.player.ReplayGainPreAmp(
                                                    with = preAmpWith.floatValue,
                                                    without = it,
                                                ),
                                            )
                                        },
                                        valueRange = -15f..15f,
                                        steps = 29,
                                        accentColor = PrimaryPink,
                                    )
                                }
                            }
                        }
                    }

                    SettingsSection.LIBRARY_STORAGE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SettingsSectionCard(
                                icon = Icons.Default.FolderSpecial,
                                title = "Biblioteca & Almacenamiento",
                                subtitle = "Escaneo, carátulas e importación",
                                accent = TertiaryCyan,
                            ) {
                                var importedCount by remember { mutableStateOf<Int?>(null) }
                                val safMediaRepository: org.michimusic.data.repository.SafMediaRepository = koinInject()
                                val openDocLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                                    androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
                                ) { uris ->
                                    if (uris.isNotEmpty()) {
                                        scope.launch {
                                            var imported = 0
                                            for (uri in uris) {
                                                val track = safMediaRepository.importAudioUri(uri)
                                                if (track != null) imported++
                                            }
                                            importedCount = imported
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(GlassFillLow)
                                        .border(1.dp, GlassBorderLow, RoundedCornerShape(12.dp))
                                        .clickable { openDocLauncher.launch(arrayOf("audio/*", "application/ogg")) }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Importar audio (SAF)",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = PureWhite,
                                            ),
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = if (importedCount != null) {
                                                "$importedCount pista(s) importada(s)"
                                            } else {
                                                "Seleccionar archivos locales o de almacenamiento externo"
                                            },
                                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = TertiaryCyan,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

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
                        }
                    }

                    SettingsSection.ACCESSIBILITY -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SettingsSectionCard(
                                icon = Icons.Default.AccessibilityNew,
                                title = "Accesibilidad & Interfaz",
                                subtitle = "Optimización visual y de movimiento",
                                accent = TertiaryCyan,
                            ) {
                                SettingSwitchRow(
                                    title = "Alto contraste",
                                    subtitle = "Resaltar bordes y texto para mayor legibilidad",
                                    checked = visualPrefs.highContrast,
                                    onCheckedChange = {
                                        visualPreferencesRepo.setHighContrast(it)
                                    },
                                )

                                Spacer(Modifier.height(12.dp))

                                SettingSwitchRow(
                                    title = "Reducir animaciones",
                                    subtitle = "Disminuir transiciones y movimiento en pantallas",
                                    checked = visualPrefs.reducedMotion,
                                    onCheckedChange = {
                                        visualPreferencesRepo.setReducedMotion(it)
                                    },
                                )
                            }
                        }
                    }

                    SettingsSection.PRIVACY -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        }
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
private fun SettingsAudioQuickActions(
    stacked: Boolean,
    onEqualizer: () -> Unit,
    onAudioRoute: () -> Unit,
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsAudioQuickActionCard(
                icon = Icons.Default.Tune, title = "Ecualizador",
                subtitle = "5 bandas y efectos", accent = PrimaryPink,
                onClick = onEqualizer, modifier = Modifier.fillMaxWidth(),
            )
            SettingsAudioQuickActionCard(
                icon = Icons.Default.Headphones, title = "Salidas de audio",
                subtitle = "DAC USB y enrutamiento", accent = TertiaryCyan,
                onClick = onAudioRoute, modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsAudioQuickActionCard(
                icon = Icons.Default.Tune, title = "Ecualizador",
                subtitle = "5 bandas y efectos", accent = PrimaryPink,
                onClick = onEqualizer, modifier = Modifier.weight(1f),
            )
            SettingsAudioQuickActionCard(
                icon = Icons.Default.Headphones, title = "Salidas de audio",
                subtitle = "DAC USB y enrutamiento", accent = TertiaryCyan,
                onClick = onAudioRoute, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsAudioQuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    GlassCard(
        modifier = modifier.heightIn(min = 72.dp),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = PureWhite, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = TextSecondary, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsCategoryNavCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MichiShapes.sm)
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
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
        backgroundColor = GlassFillLow,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = TextSecondary, fontSize = 12.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
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
        modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
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
