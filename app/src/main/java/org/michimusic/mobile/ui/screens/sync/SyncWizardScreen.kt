package org.michimusic.mobile.ui.screens.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.core.models.DeliveryProfile
import org.michimusic.core.models.IssueSeverity
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.ProfileAvailability
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncPlan
import org.michimusic.core.models.SyncScope
import org.michimusic.core.models.SyncSelection
import org.michimusic.core.sync.StoragePreflightEvaluator
import org.michimusic.core.sync.SyncPreflightService
import org.michimusic.core.sync.SyncProfileNegotiator
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.ErrorColor
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

enum class SyncWizardStep {
    SELECTION,
    QUALITY,
    REVIEW
}

@Composable
fun SyncWizardScreen(
    endpointId: String,
    endpointName: String,
    availableStorageBytes: Long,
    totalStorageBytes: Long,
    currentLocalMusicBytes: Long,
    candidateTracks: List<ManifestTrack>,
    serverCapabilities: Set<String> = emptySet(),
    serverRepresentations: List<String> = emptyList(),
    localFingerprints: Set<String> = emptySet(),
    existingPartials: Map<String, Long> = emptyMap(),
    onStartSync: (SyncPlan) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentStep by remember { mutableStateOf(SyncWizardStep.SELECTION) }
    var selectedScope by remember { mutableStateOf(SyncScope.FULL_LIBRARY) }
    var selectedProfile by remember { mutableStateOf(DeliveryProfile.ORIGINAL) }
    val scrollState = rememberScrollState()

    androidx.compose.runtime.LaunchedEffect(currentStep) {
        scrollState.scrollTo(0)
    }

    val availableProfiles = remember(serverCapabilities, serverRepresentations) {
        SyncProfileNegotiator.evaluateAvailableProfiles(serverCapabilities, serverRepresentations)
    }

    val preflightService = remember { SyncPreflightService() }

    val currentSelection = remember(selectedScope) {
        SyncSelection(scope = selectedScope)
    }

    val deliveryChoice = remember(selectedProfile, serverCapabilities, serverRepresentations) {
        SyncProfileNegotiator.resolveDeliveryChoice(selectedProfile, serverCapabilities, serverRepresentations)
    }

    val syncPlan = remember(currentSelection, deliveryChoice, availableStorageBytes, candidateTracks, localFingerprints, existingPartials) {
        preflightService.buildPlan(
            endpointId = endpointId,
            endpointName = endpointName,
            selection = currentSelection,
            deliveryChoice = deliveryChoice,
            availableStorageBytes = availableStorageBytes,
            candidateTracks = candidateTracks,
            localTrackFingerprints = localFingerprints,
            existingPartials = existingPartials,
        )
    }

    Scaffold(
        containerColor = SurfaceObsidian,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        when (currentStep) {
                            SyncWizardStep.SELECTION -> onBack()
                            SyncWizardStep.QUALITY -> currentStep = SyncWizardStep.SELECTION
                            SyncWizardStep.REVIEW -> currentStep = SyncWizardStep.QUALITY
                        }
                    },
                    modifier = Modifier.testTag("sync_wizard_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = PureWhite
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Asistente de Sincronización",
                        style = MichiTypography.sectionTitle,
                        color = PureWhite,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "Nodo: $endpointName",
                        style = MichiTypography.metadata,
                        color = TertiaryCyan,
                    )
                }
            }
        },
        modifier = modifier.testTag("sync_wizard_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Step Indicator Header
            StepProgressHeader(currentStep = currentStep)

            Spacer(modifier = Modifier.height(20.dp))

            when (currentStep) {
                SyncWizardStep.SELECTION -> {
                    SelectionStep(
                        selectedScope = selectedScope,
                        totalCandidates = candidateTracks.size,
                        onSelectScope = { selectedScope = it },
                        onNext = { currentStep = SyncWizardStep.QUALITY }
                    )
                }
                SyncWizardStep.QUALITY -> {
                    QualityStep(
                        profiles = availableProfiles,
                        selectedProfile = selectedProfile,
                        onSelectProfile = { selectedProfile = it },
                        onNext = { currentStep = SyncWizardStep.REVIEW }
                    )
                }
                SyncWizardStep.REVIEW -> {
                    ReviewStep(
                        plan = syncPlan,
                        totalStorageBytes = totalStorageBytes,
                        systemAndOtherBytes = (totalStorageBytes - availableStorageBytes - currentLocalMusicBytes).coerceAtLeast(0L),
                        currentLocalMusicBytes = currentLocalMusicBytes,
                        onStartSync = { onStartSync(syncPlan) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepProgressHeader(currentStep: SyncWizardStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepChip(number = "1", title = "Selección", isActive = currentStep == SyncWizardStep.SELECTION, isDone = currentStep.ordinal > 0)
        StepChip(number = "2", title = "Calidad", isActive = currentStep == SyncWizardStep.QUALITY, isDone = currentStep.ordinal > 1)
        StepChip(number = "3", title = "Preflight", isActive = currentStep == SyncWizardStep.REVIEW, isDone = false)
    }
}

@Composable
private fun StepChip(number: String, title: String, isActive: Boolean, isDone: Boolean) {
    val bg = when {
        isActive -> AccentCoral.copy(alpha = 0.2f)
        isDone -> TertiaryCyan.copy(alpha = 0.15f)
        else -> GlassFillLow
    }
    val border = when {
        isActive -> AccentCoral
        isDone -> TertiaryCyan
        else -> GlassBorderLow
    }
    val textCol = when {
        isActive -> AccentCoral
        isDone -> TertiaryCyan
        else -> TextDim
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = number,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = textCol
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MichiTypography.metadata,
            color = if (isActive || isDone) PureWhite else TextDim,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SelectionStep(
    selectedScope: SyncScope,
    totalCandidates: Int,
    onSelectScope: (SyncScope) -> Unit,
    onNext: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "¿Qué deseas sincronizar?",
            style = MichiTypography.cardTitle,
            color = PureWhite,
            fontSize = 20.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Elige el alcance de contenido para transferir desde el servidor a este móvil.",
            style = MichiTypography.metadata,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(20.dp))

        ScopeOptionCard(
            scope = SyncScope.FULL_LIBRARY,
            title = "Biblioteca Completa",
            subtitle = "$totalCandidates pistas disponibles en el catálogo remoto",
            isSelected = selectedScope == SyncScope.FULL_LIBRARY,
            onSelect = { onSelectScope(SyncScope.FULL_LIBRARY) },
            testTag = "sync_wizard_selection_full_library"
        )

        Spacer(modifier = Modifier.height(12.dp))

        ScopeOptionCard(
            scope = SyncScope.PLAYLISTS,
            title = "Playlists Remotas",
            subtitle = "Sincroniza todas las listas de reproducción del servidor",
            isSelected = selectedScope == SyncScope.PLAYLISTS,
            onSelect = { onSelectScope(SyncScope.PLAYLISTS) },
            testTag = "sync_wizard_selection_playlists"
        )

        Spacer(modifier = Modifier.height(12.dp))

        ScopeOptionCard(
            scope = SyncScope.ALBUMS,
            title = "Álbumes Específicos",
            subtitle = "Selecciona álbumes completos para llevar en offline",
            isSelected = selectedScope == SyncScope.ALBUMS,
            onSelect = { onSelectScope(SyncScope.ALBUMS) },
            testTag = "sync_wizard_selection_albums"
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = PureWhite),
            shape = MichiShapes.md,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("sync_wizard_next_button")
        ) {
            Text("Siguiente: Calidad y Perfil", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ScopeOptionCard(
    scope: SyncScope,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    testTag: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MichiShapes.md)
            .clickable(onClick = onSelect)
            .border(
                1.dp,
                if (isSelected) AccentCoral else GlassBorderLow,
                MichiShapes.md
            )
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) GlassFillHigh else GlassFillLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MichiTypography.cardTitle, color = PureWhite, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, style = MichiTypography.metadata, color = TextSecondary, fontSize = 13.sp)
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Seleccionado",
                    tint = AccentCoral,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun QualityStep(
    profiles: List<ProfileAvailability>,
    selectedProfile: DeliveryProfile,
    onSelectProfile: (DeliveryProfile) -> Unit,
    onNext: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Perfil de Entrega y Calidad",
            style = MichiTypography.cardTitle,
            color = PureWhite,
            fontSize = 20.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Determina el formato de entrega. Las opciones de transcodificación requieren soporte explícito anunciado por el servidor.",
            style = MichiTypography.metadata,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(20.dp))

        profiles.forEach { item ->
            ProfileOptionCard(
                item = item,
                isSelected = selectedProfile == item.profile,
                onSelect = { if (item.isAvailable) onSelectProfile(item.profile) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = PureWhite),
            shape = MichiShapes.md,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("sync_wizard_quality_next_button")
        ) {
            Text("Siguiente: Revisar y Preflight", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ProfileOptionCard(
    item: ProfileAvailability,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val enabled = item.isAvailable
    val borderColor = when {
        isSelected && enabled -> AccentCoral
        !enabled -> GlassBorderLow.copy(alpha = 0.5f)
        else -> GlassBorderLow
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MichiShapes.md)
            .clickable(enabled = enabled, onClick = onSelect)
            .border(1.dp, borderColor, MichiShapes.md)
            .testTag("sync_wizard_profile_${item.profile.name}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected && enabled) GlassFillHigh else GlassFillLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.profile.label,
                        style = MichiTypography.cardTitle,
                        color = if (enabled) PureWhite else TextDim,
                        fontSize = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.codecDescription,
                        style = MichiTypography.metadata,
                        color = if (enabled) TertiaryCyan else TextDim,
                        fontSize = 12.sp,
                    )
                }

                if (!enabled) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("sync_wizard_profile_disabled_badge")
                    ) {
                        Text(
                            text = "No disponible",
                            style = MichiTypography.metadata,
                            color = TextDim,
                            fontSize = 11.sp,
                        )
                    }
                } else if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Seleccionado",
                        tint = AccentCoral,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.reasonIfNot ?: item.profile.description,
                style = MichiTypography.metadata,
                color = if (enabled) TextSecondary else TextDim,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ReviewStep(
    plan: SyncPlan,
    totalStorageBytes: Long,
    systemAndOtherBytes: Long,
    currentLocalMusicBytes: Long,
    onStartSync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Revisión y Preflight de Almacenamiento",
            style = MichiTypography.cardTitle,
            color = PureWhite,
            fontSize = 20.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Evaluación determinista de espacio en disco, reserva de seguridad de 100 MB y deduplicación.",
            style = MichiTypography.metadata,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Visual Storage Breakdown Bar
        StorageBreakdownBar(
            totalStorageBytes = totalStorageBytes,
            systemAndOtherBytes = systemAndOtherBytes,
            currentLocalMusicBytes = currentLocalMusicBytes,
            newSyncBytes = plan.totalDownloadBytes,
            safetyBufferBytes = plan.safetyBufferBytes,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Plan metrics breakdown
        PlanMetricsCard(plan = plan)

        Spacer(modifier = Modifier.height(16.dp))

        // Issues display
        plan.issues.forEach { issue ->
            IssueBanner(issue = issue)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (plan.isExecutable) {
            Button(
                onClick = onStartSync,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = PureWhite),
                shape = MichiShapes.md,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sync_wizard_start_button")
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Iniciar Sincronización", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } else {
            Button(
                onClick = {},
                enabled = false,
                colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF2A2C33), disabledContentColor = TextDim),
                shape = MichiShapes.md,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sync_wizard_start_button_disabled")
            ) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = ErrorColor)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sincronización Bloqueada", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun PlanMetricsCard(plan: SyncPlan) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MichiShapes.md)
            .border(1.dp, GlassBorderLow, MichiShapes.md),
        colors = CardDefaults.cardColors(containerColor = GlassFillLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            MetricRow(label = "Pistas seleccionadas:", value = "${plan.totalSelectedTracks}")
            MetricRow(label = "Ya disponibles localmente (omitidas):", value = "${plan.tracksSkippedCount}")
            if (plan.tracksToResumeCount > 0) {
                MetricRow(label = "Reanudables (.part parciales):", value = "${plan.tracksToResumeCount}")
            }
            MetricRow(label = "Pistas a descargar:", value = "${plan.tracksToDownloadCount}")
            MetricRow(label = "Carga neta estimada:", value = StoragePreflightEvaluator.formatBytes(plan.totalDownloadBytes), isHighlight = true)
            MetricRow(label = "Espacio libre tras sincronización:", value = StoragePreflightEvaluator.formatBytes(plan.remainingStorageAfterSyncBytes))
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, isHighlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MichiTypography.metadata, color = TextSecondary, fontSize = 13.sp)
        Text(
            text = value,
            style = MichiTypography.metadata,
            color = if (isHighlight) AccentCoral else PureWhite,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun IssueBanner(issue: org.michimusic.core.models.SyncPreflightIssue) {
    val isBlocking = issue.severity == IssueSeverity.BLOCKING
    val bg = if (isBlocking) Color(0x33FF5252) else Color(0x33FFB74D)
    val border = if (isBlocking) ErrorColor else Color(0xFFFFB74D)
    val icon = if (isBlocking) Icons.Filled.Error else Icons.Filled.Warning
    val tag = if (isBlocking) "sync_wizard_issue_blocking" else "sync_wizard_issue_warning"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = border,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = issue.message,
            style = MichiTypography.metadata,
            color = PureWhite,
            fontSize = 12.sp,
        )
    }
}
