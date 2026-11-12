package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.R
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/** Index is a lightweight navigation surface, not a duplicate settings engine. */
@Composable
internal fun SettingsHubIndex(
    replayGainSummary: String,
    appearanceSummary: String,
    historySummary: String,
    syncSummary: String,
    onOpen: (SettingsSection) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val results = remember(search) { filterSettingsDestinations(search) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().testTag("settings_hub")) {
        Text("ATELIER · MICHI MUSIC", style = MichiTypography.screenEyebrow,
            color = PrimaryPink)
        Text("Tu música, a tu manera", style = MichiTypography.sectionTitle,
            color = PureWhite)
        Text("Ajusta el sonido, la colección y el ecosistema desde cada apartado.",
            style = MichiTypography.metadata, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        AeroSurface(
            role = AeroSurfaceRole.Content,
            finish = AeroFinish.Plain,
            modifier = Modifier.fillMaxWidth().testTag("settings_search_surface"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                BasicTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
                    textStyle = TextStyle(color = PureWhite, fontSize = 16.sp),
                    cursorBrush = SolidColor(PrimaryPink),
                    modifier = Modifier.weight(1f).testTag("settings_search_input")
                        .semantics { contentDescription = "Buscar ajustes" },
                    decorationBox = { inner ->
                        if (search.isEmpty()) Text("Buscar ajustes", color = TextSecondary)
                        inner()
                    },
                )
                if (search.isNotEmpty()) {
                    IconButton(onClick = { search = "" },
                        modifier = Modifier.testTag("settings_search_clear")) {
                        Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda", tint = PureWhite)
                    }
                }
            }
        }
        if (results.isEmpty()) {
            Text("No se encontraron ajustes. Prueba con otro nombre.",
                style = MichiTypography.metadata, color = TextSecondary,
                modifier = Modifier.padding(vertical = 20.dp).testTag("settings_search_empty"))
        }
        SettingsIndexGroup.entries.forEach { group ->
            val items = results.filter { it.group == group }
            if (items.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(group.caption, fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp, letterSpacing = 1.sp, color = TertiaryCyan)
                items.forEach { entry ->
                    val info = when (entry.section) {
                        SettingsSection.PLAYBACK -> "ReplayGain: $replayGainSummary"
                        SettingsSection.APPEARANCE -> "Aero $appearanceSummary"
                        SettingsSection.PRIVACY -> historySummary
                        SettingsSection.ECOSYSTEM -> syncSummary
                        else -> entry.summary
                    }
                    val icon: ImageVector = when (entry.section) {
                        SettingsSection.PLAYBACK -> Icons.Default.PlayArrow
                        SettingsSection.AUDIO_OUTPUT -> Icons.Default.Headphones
                        SettingsSection.PROCESSING -> Icons.Default.GraphicEq
                        SettingsSection.LIBRARY_STORAGE -> Icons.Default.Storage
                        SettingsSection.ECOSYSTEM -> Icons.Default.Devices
                        SettingsSection.APPEARANCE -> Icons.Default.Palette
                        SettingsSection.ACCESSIBILITY -> Icons.Default.AccessibilityNew
                        SettingsSection.PRIVACY -> Icons.Default.Security
                        SettingsSection.SYSTEM -> Icons.Default.Settings
                        else -> Icons.Default.Info
                    }
                    val wizardIcon: Int? = when (entry.section) {
                        // Only the protected Michi emblem stays as artwork; every
                        // other destination uses one coherent Material family.
                        SettingsSection.ABOUT -> R.drawable.wizard_brand_official
                        else -> null
                    }
                    val accent = when (entry.section) {
                        SettingsSection.ECOSYSTEM, SettingsSection.PRIVACY, SettingsSection.ABOUT -> SecondaryPurple
                        SettingsSection.AUDIO_OUTPUT -> TertiaryCyan
                        else -> PrimaryPink
                    }
                    SettingsCategoryNavCard(
                        icon = icon, iconRes = wizardIcon,
                        title = entry.title, subtitle = info, accent = accent,
                        onClick = { onOpen(entry.section) },
                        testTag = "settings_route_${entry.section.name.lowercase()}",
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Always the last index surface. External browser opens only on direct tap.
        SettingsDonationCard()
    }
}
