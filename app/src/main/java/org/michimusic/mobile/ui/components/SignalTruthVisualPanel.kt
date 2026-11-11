package org.michimusic.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.TextSecondary

/** The functional owner supplies these *verbatim*. No UI evidence conversion. */
internal data class SignalTruthVisualSummary(
    val destination: String,
    val certainty: String,
)

/** Distinct lanes make RTP / local monitor branches legible without fabricating edges. */
internal data class SignalTruthVisualLane(
    val title: String,
    val steps: List<SignalTruthUiStep>,
)

/**
 * One summary slab, branch-safe lanes, two independent disclosures. The R27
 * functional module alone decides what the destination, certainty, rows and
 * connections mean. The sheet owner supplies scrolling and lifecycle policy.
 * This is intentionally not wired into SignalTruthMobileSheet until R30 is reconciled.
 */
@Composable
internal fun SignalTruthVisualPanel(
    summary: SignalTruthVisualSummary,
    lanes: List<SignalTruthVisualLane>,
    onOpenTechnical: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    settingsAvailable: Boolean = false,
    motionEnabled: Boolean = false,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val supportive = if (highContrast) PureWhite else TextSecondary
    Column(
        modifier = modifier.fillMaxWidth().testTag("signal_truth_visual_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = AeroGraphite.copy(alpha = 0.72f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(if (highContrast) 1.2.dp else 0.75.dp,
                if (highContrast) PureWhite.copy(alpha = 0.86f) else AeroHairline),
            modifier = Modifier.fillMaxWidth().testTag("signal_truth_visual_summary"),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("DESTINO Y CERTEZA", color = supportive,
                    style = MaterialTheme.typography.labelSmall)
                Text(summary.destination.ifBlank { "Destino no informado" }, color = PureWhite,
                    style = MaterialTheme.typography.titleMedium)
                Text(summary.certainty.ifBlank { "Sin información suficiente sobre la salida" }, color = supportive,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Recorrido de audio", color = PureWhite,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }
                .testTag("signal_truth_visual_route_heading"))

        if (lanes.isEmpty() || lanes.all { it.steps.isEmpty() }) {
            Text("Todavía no hay un recorrido confirmado para mostrar",
                color = supportive, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("signal_truth_visual_no_route"))
        } else {
            lanes.forEachIndexed { laneIndex, lane ->
                if (lane.steps.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .testTag("signal_truth_visual_lane_$laneIndex"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // The label comes from R27; lanes are not joined visually.
                        if (lanes.size > 1 && lane.title.isNotBlank()) {
                            Text(lane.title, color = supportive,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.semantics { heading() })
                        }
                        SignalTruthUiTrail(steps = lane.steps, motionEnabled = motionEnabled)
                    }
                }
            }
        }
        SignalTruthUiDisclosureActions(
            onOpenTechnical = onOpenTechnical,
            onOpenSettings = onOpenSettings,
            settingsAvailable = settingsAvailable,
        )
    }
}
