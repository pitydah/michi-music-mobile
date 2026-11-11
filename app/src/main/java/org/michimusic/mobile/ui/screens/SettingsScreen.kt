package org.michimusic.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.onboarding.OnboardingSnapshot
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan

/**
 * Thin Settings shell. Each destination owns its state and observers; the shell
 * only keeps the selected section, the back contract and the shared chrome.
 */
@Composable
fun SettingsScreen(
    initialSection: SettingsSection = SettingsSection.ROOT,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onReopenOnboarding: () -> Unit = {},
    onNavigateToDevices: () -> Unit = {},
    onNavigateToAudioRoute: () -> Unit = {},
    onboardingSnapshot: OnboardingSnapshot? = null,
) {
    var currentSection by rememberSaveable { mutableStateOf(initialSection) }
    var showEqualizerDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        if (currentSection == SettingsSection.ROOT) onNavigateBack()
        else currentSection = SettingsSection.ROOT
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian),
    ) {
        AeroPremiumBackdrop(accent = TertiaryCyan)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = MichiSpacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MichiIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    onClick = {
                        if (currentSection == SettingsSection.ROOT) onNavigateBack()
                        else currentSection = SettingsSection.ROOT
                    },
                    tint = PureWhite,
                    iconSize = 24.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "settings_back_button",
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "AJUSTES",
                        style = MichiTypography.screenEyebrow,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = settingsDestinationTitle(currentSection),
                        style = MichiTypography.screenTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (currentSection) {
                    SettingsSection.ROOT -> SettingsHubLanding(
                        onOpen = { currentSection = it },
                    )
                    SettingsSection.AUDIO_QUALITY, SettingsSection.PLAYBACK -> SettingsPlaybackPanel()
                    SettingsSection.PROCESSING -> SettingsProcessingPanel(
                        onOpenEqualizer = { showEqualizerDialog = true },
                    )
                    SettingsSection.AUDIO_OUTPUT -> SettingsOutputPanel(
                        onOpenRoute = onNavigateToAudioRoute,
                    )
                    SettingsSection.LIBRARY_STORAGE -> SettingsLibraryPanel()
                    SettingsSection.ECOSYSTEM -> SettingsEcosystemPanel(
                        onNavigateToDevices = onNavigateToDevices,
                    )
                    SettingsSection.APPEARANCE -> SettingsAppearancePanel()
                    SettingsSection.ACCESSIBILITY -> SettingsAccessibilityPanel()
                    SettingsSection.PRIVACY -> SettingsPrivacyPanel()
                    SettingsSection.SYSTEM -> SettingsSystemPanel(
                        onNavigateToDiagnostics = onNavigateToDiagnostics,
                        onReopenOnboarding = onReopenOnboarding,
                        onNavigateToAudioRoute = onNavigateToAudioRoute,
                        onNavigateToDevices = onNavigateToDevices,
                        onboardingSnapshot = onboardingSnapshot,
                    )
                    SettingsSection.ABOUT -> SettingsAboutPanel()
                }
            }
        }
    }

    if (showEqualizerDialog) {
        EqualizerDialog(onDismiss = { showEqualizerDialog = false })
    }
}
