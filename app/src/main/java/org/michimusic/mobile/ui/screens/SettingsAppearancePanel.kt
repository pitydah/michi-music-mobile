package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import androidx.compose.material3.TextButton
import android.os.Build
import android.os.PowerManager
import android.app.ActivityManager
import org.michimusic.mobile.ui.theme.AeroAppearanceProfile
import org.michimusic.mobile.ui.theme.AeroCoverFlowReflection
import org.michimusic.mobile.ui.theme.glassSuspensionReason
import org.michimusic.mobile.ui.theme.AeroSeekerStyle
import org.michimusic.mobile.ui.components.AeroSegmentedChoice
import org.michimusic.mobile.ui.components.AeroSegmentTreatment
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary






/** Aero appearance remains owned by VisualPreferencesRepository. */
@Composable
internal fun SettingsAppearancePanel() {
    val visualPreferencesRepo: org.michimusic.mobile.ui.theme.VisualPreferencesRepository = koinInject()
    val visualPrefs by visualPreferencesRepo.preferences.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionCard(
            icon = Icons.Default.Palette,
            title = "Apariencia Aero",
            subtitle = "Intensidad de materiales y microtexturas",
            accent = PrimaryPink,
        ) {
            Text(
                "Acabado de superficies",
                color = TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AeroSegmentedChoice(
                    label = "Discreto",
                    selected = visualPrefs.appearanceProfile == AeroAppearanceProfile.DISCRETO,
                    onClick = { visualPreferencesRepo.setAppearanceProfile(AeroAppearanceProfile.DISCRETO) },
                    modifier = Modifier.weight(1f),
                    treatment = AeroSegmentTreatment.Filter,
                    testTag = "settings_appearance_discreet",
                )
                AeroSegmentedChoice(
                    label = "Equilibrado",
                    selected = visualPrefs.appearanceProfile == AeroAppearanceProfile.EQUILIBRADO,
                    onClick = { visualPreferencesRepo.setAppearanceProfile(AeroAppearanceProfile.EQUILIBRADO) },
                    modifier = Modifier.weight(1f),
                    treatment = AeroSegmentTreatment.Filter,
                    testTag = "settings_appearance_balanced",
                )
            }
            Spacer(Modifier.height(8.dp))
            AeroSegmentedChoice(
                label = "Cristalino · banner desenfocado",
                selected = visualPrefs.appearanceProfile == AeroAppearanceProfile.CRISTALINO,
                onClick = { visualPreferencesRepo.setAppearanceProfile(AeroAppearanceProfile.CRISTALINO) },
                modifier = Modifier.fillMaxWidth(),
                treatment = AeroSegmentTreatment.Filter,
                testTag = "settings_appearance_crystalline",
            )
            val appearanceContext = LocalContext.current
            val glassReason = glassSuspensionReason(
                visualPrefs.appearanceProfile, Build.VERSION.SDK_INT,
                visualPrefs.highContrast, visualPrefs.reducedMotion,
                (appearanceContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isPowerSaveMode == true,
                (appearanceContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.isLowRamDevice == true,
            )
            if (glassReason != null) Text(
                glassReason, color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.testTag("settings_appearance_glass_status"),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Cristalino desenfoca únicamente el banner real de una playlist " +
                    "en Android 12+; si falta imagen, compatibilidad o hay alto " +
                    "contraste/movimiento reducido, ahorro de batería o memoria limitada, " +
                    "conserva Aero ahumado. " +
                    "No incluye refracción AGSL ni blur de listas.",
                color = TextSecondary, fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            SettingSwitchRow(
                title = "Microtexturas",
                subtitle = "Grano fino y estable en los materiales Aero",
                checked = visualPrefs.microtexturesEnabled,
                onCheckedChange = { visualPreferencesRepo.setMicrotexturesEnabled(it) },
                testTag = "settings_appearance_microtextures",
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "CoverFlow",
                color = TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Intensidad del marco y la sombra de contacto. No duplica " +
                    "carátulas ni altera gestos, orden o selección.",
                color = TextSecondary, fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AeroCoverFlowReflection.entries.forEach { mode ->
                    AeroSegmentedChoice(
                        label = mode.label,
                        selected = visualPrefs.coverFlowReflection == mode,
                        onClick = { visualPreferencesRepo.setCoverFlowReflection(mode) },
                        modifier = Modifier.weight(1f),
                        treatment = AeroSegmentTreatment.Filter,
                        testTag = "settings_coverflow_reflection_${mode.name.lowercase()}",
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { visualPreferencesRepo.resetAppearance() },
                modifier = Modifier.testTag("settings_appearance_reset"),
            ) {
                Text("Restablecer apariencia", color = PrimaryPink)
            }
            Spacer(Modifier.height(18.dp))
            Text("Reproducción · Barra de progreso",
                color = TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            // All five styles are selectable. Waveform choices are honest:
            // a missing local PCM decode shows the exact original seeker.
            AeroSeekerStyle.entries.forEach { choice ->
                AeroSegmentedChoice(
                    label = choice.label,
                    selected = visualPrefs.seekerStyle == choice,
                    onClick = { visualPreferencesRepo.setSeekerStyle(choice) },
                    modifier = Modifier.fillMaxWidth(),
                    treatment = AeroSegmentTreatment.Filter,
                    testTag = "settings_seeker_${choice.name.lowercase()}",
                )
                SettingsSeekerPreview(choice)
                Spacer(Modifier.height(9.dp))
            }
            Text("Vistas previas esquemáticas, sin audio ni medidas reales. " +
                "Las formas de onda usan un patrón demostrativo fijo.",
                color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.testTag("settings_seeker_demo_disclosure"))
            Spacer(Modifier.height(6.dp))
            Text("Barras y Continuo analizan PCM real de archivos locales " +
                "accesibles. Si falta el archivo, permiso o códec, se muestra " +
                "la barra Original. Liquid Glass mantiene su acabado compatible.",
                color = TextSecondary, fontSize = 12.sp)
        }
    }
}
