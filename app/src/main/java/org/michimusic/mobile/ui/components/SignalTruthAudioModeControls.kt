package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.michimusic.core.models.AudioProcessingMode
import org.michimusic.mobile.playback.AudioModeQuickEngine
import org.michimusic.mobile.playback.AudioModeQuickResult
import org.michimusic.mobile.playback.SignalDestination
import org.michimusic.mobile.playback.SignalTruthAudioModeCoordinator
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.playback.SignalTruthLocalAuthorityLease
import org.michimusic.player.PlayerDependencies

/** Uses the live AudioModeManager; does not create a second mode preference. */
@Composable
internal fun SignalTruthAudioModeControls() {
    val manager = PlayerDependencies.audioModeManager
    val outputAuthority by PlayerDependencies.outputAuthority.collectAsStateWithLifecycle()
    val current by (manager?.currentMode?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(AudioProcessingMode.DSP)
    })
    val coordinator = remember(manager) {
        SignalTruthAudioModeCoordinator(object : AudioModeQuickEngine {
            override fun currentMode(): AudioProcessingMode? = manager?.currentMode?.value
            override fun configure(mode: AudioProcessingMode): Boolean {
                val target = manager ?: return false
                target.setMode(mode)
                PlayerDependencies.signalTruthTracker?.recompute()
                return target.currentMode.value == mode
            }
        }, hasLocalAuthority = { PlayerDependencies.outputAuthority.value.let {
            it.isLocal && it.endpointId == "local_phone"
        } }, authorityLease = { PlayerDependencies.outputAuthority.value.let {
                SignalTruthLocalAuthorityLease(it.endpointId, it.isLocal, it.revision)
            } })
    }
    var feedback by remember { mutableStateOf<AudioModeQuickResult?>(null) }
    val enabled = manager != null && outputAuthority.isLocal && outputAuthority.endpointId == "local_phone"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Modo de procesamiento", style = MaterialTheme.typography.titleSmall)
        Text("Transparente omite efectos del telefono; no significa bit-perfect.",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(AudioProcessingMode.AUTO, AudioProcessingMode.TRANSPARENT,
                AudioProcessingMode.DSP).forEach { mode ->
                OutlinedButton(
                    enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    onClick = { feedback = coordinator.choose(SignalDestination.LOCAL, mode) },
                ) {
                    Text((if (current == mode) "✓ " else "") + when (mode) {
                        AudioProcessingMode.AUTO -> "Auto"
                        AudioProcessingMode.TRANSPARENT -> "Sin efectos"
                        AudioProcessingMode.DSP -> "DSP"
                    })
                }
            }
        }
        feedback?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant) }
        if (!enabled) Text("Sin autoridad local o sin motor de audio disponible.",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
    }
}
