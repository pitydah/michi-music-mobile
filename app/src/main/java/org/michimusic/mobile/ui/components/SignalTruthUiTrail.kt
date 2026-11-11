package org.michimusic.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.MichiAuroraCyan
import org.michimusic.mobile.ui.theme.MichiAuroraLavender
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/** Visual categorization only; this layer NEVER interprets audio evidence. */
internal enum class SignalTruthUiTone { NEUTRAL, OBSERVED, CONFIGURED, UNKNOWN }

/**
 * An immutable display row supplied by the separately integrated functional owner.
 * connectedToPrevious is explicit authorization, never inferred from adjacency.
 */
internal data class SignalTruthUiStep(
    val id: String,
    val title: String,
    val detail: String,
    val stateLabel: String,
    val tone: SignalTruthUiTone,
    val connectedToPrevious: Boolean = false,
)

/** Duplicate identifiers are ambiguous: display rows but never connect them. */
internal fun signalTruthUiConnectorPairs(steps: List<SignalTruthUiStep>): List<Pair<String, String>> {
    if (steps.any { it.id.isBlank() } || steps.map { it.id }.toSet().size != steps.size) {
        return emptyList()
    }
    return steps.zipWithNext().mapNotNull { (previous, current) ->
        if (current.connectedToPrevious) previous.id to current.id else null
    }
}

private fun stateTint(tone: SignalTruthUiTone): Color = when (tone) {
    SignalTruthUiTone.NEUTRAL -> OnSurfaceVariant
    SignalTruthUiTone.OBSERVED -> MichiAuroraCyan
    SignalTruthUiTone.CONFIGURED -> MichiAuroraLavender
    SignalTruthUiTone.UNKNOWN -> Color(0xFFE2B579) // limited caution accent
}

/**
 * Obsidian/Aero presentation only. This widget receives already-resolved strings,
 * state labels and true links; it cannot access SignalGraphUi, USB, RTP or DSP.
 * It is not wired into the popup until R30 is reconciled on a real checkout.
 * Motion policy comes from the screen owner and defaults to OFF.
 */
@Composable
internal fun SignalTruthUiTrail(
    steps: List<SignalTruthUiStep>,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = false,
) {
    if (steps.isEmpty()) {
        Text("Recorrido sin información suficiente", color = OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.testTag("signal_truth_visual_empty"))
        return
    }

    val ids = remember(steps) { steps.map { it.id } }
    var centers by remember(ids) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var phase by remember(ids) { mutableFloatStateOf(0f) }
    val connected = remember(steps) { signalTruthUiConnectorPairs(steps) }
    val canAnimate = motionEnabled && connected.isNotEmpty()
    LaunchedEffect(ids, canAnimate) {
        if (!canAnimate) return@LaunchedEffect
        while (isActive) {
            delay(125L) // single low-frequency 8-fps path pulse, no per-row loops
            phase = (phase + 0.0625f) % 1f
        }
    }

    Box(modifier = modifier.fillMaxWidth().testTag("signal_truth_visual_trail")) {
        Canvas(Modifier.matchParentSize()) {
            val spans = connected.mapNotNull { (from, to) ->
                val a = centers[from]
                val b = centers[to]
                if (a != null && b != null && b > a) a to b else null
            }
            val x = 14.dp.toPx() // exact centre of the 28-dp row marker
            val lengthTotal = spans.sumOf { (a, b) -> (b - a).toDouble() }.toFloat()
            val at = phase * lengthTotal
            var accumulated = 0f
            spans.forEach { (start, end) ->
                val length = end - start
                drawLine(color = AeroHairline,
                    start = Offset(x, start), end = Offset(x, end),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                if (canAnimate && lengthTotal > 0f) {
                    val pulse = 18.dp.toPx()
                    val from = (at - accumulated - pulse).coerceIn(0f, length)
                    val to = (at - accumulated).coerceIn(0f, length)
                    if (to > from) drawLine(MichiAuroraCyan.copy(alpha = 0.85f),
                        Offset(x, start + from), Offset(x, start + to),
                        strokeWidth = 2.75.dp.toPx(), cap = StrokeCap.Round)
                }
                accumulated += length
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            steps.forEachIndexed { index, step ->
                val tint = stateTint(step.tone)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .onGloballyPositioned { coords ->
                            val y = coords.positionInParent().y + coords.size.height / 2f
                            if (centers[step.id] != y) centers = centers + (step.id to y)
                        }
                        .semantics(mergeDescendants = true) { }
                        .testTag("signal_truth_ui_stage_$index"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(20.dp).background(tint.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(6.dp).background(tint, CircleShape))
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(step.title, color = PureWhite,
                            style = MaterialTheme.typography.titleSmall)
                        Text(step.detail, color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall)
                        if (step.stateLabel.isNotBlank()) {
                            Text(step.stateLabel, color = tint,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Two separate disclosure affordances, not new audio settings controllers.
 * Screens may replace these callbacks with their canonical R30 destinations.
 */
@Composable
internal fun SignalTruthUiDisclosureActions(
    onOpenTechnical: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    settingsAvailable: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenTechnical,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("signal_truth_visual_details")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Detalles técnicos", style = MaterialTheme.typography.labelLarge,
                    color = PureWhite)
                Spacer(Modifier.weight(1f))
                Text("›", style = MaterialTheme.typography.titleMedium,
                    color = OnSurfaceVariant)
            }
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(AeroHairline))
        TextButton(onClick = onOpenSettings,
            enabled = settingsAvailable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("signal_truth_visual_settings")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ajustes de audio", style = MaterialTheme.typography.labelLarge,
                    color = if (settingsAvailable) PureWhite else OnSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("›", style = MaterialTheme.typography.titleMedium,
                    color = OnSurfaceVariant)
            }
        }
    }
}
