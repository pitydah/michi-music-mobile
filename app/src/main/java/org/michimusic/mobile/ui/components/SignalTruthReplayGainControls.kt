package org.michimusic.mobile.ui.components

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.michimusic.mobile.playback.ReplayGainQuickEngine
import org.michimusic.mobile.playback.ReplayGainQuickResult
import org.michimusic.mobile.playback.ReplayGainQuickStore
import org.michimusic.mobile.playback.SignalDestination
import org.michimusic.mobile.playback.SignalTruthReplayGainCoordinator
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.playback.SignalTruthLocalAuthorityLease
import org.michimusic.player.PlayerDependencies
import org.michimusic.player.ReplayGainMode
import org.michimusic.player.ReplayGainPreAmp

/** Existing preference authority is SettingsScreen / michi_settings.
 * This panel submits local intents through one coordinator; it is not a second DSP.
 */
@Composable
internal fun SignalTruthReplayGainControls() {
    val context = LocalContext.current
    val outputAuthority by PlayerDependencies.outputAuthority.collectAsStateWithLifecycle()
    val coordinator = remember(context) {
        val prefs = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
        SignalTruthReplayGainCoordinator(
            engine = object : ReplayGainQuickEngine {
                override fun currentMode(): ReplayGainMode? =
                    PlayerDependencies.replayGainProcessor?.currentMode

                override fun configure(mode: ReplayGainMode, withPreampDb: Float, withoutPreampDb: Float): Boolean {
                    if (PlayerDependencies.replayGainProcessor == null) return false
                    PlayerDependencies.configureReplayGain(
                        mode, ReplayGainPreAmp(with = withPreampDb, without = withoutPreampDb))
                    PlayerDependencies.signalTruthTracker?.recompute()
                    return PlayerDependencies.replayGainProcessor?.currentMode == mode
                }
            },
            store = object : ReplayGainQuickStore {
                override fun withPreampDb(): Float = prefs.getFloat("replaygain_preamp_with", 0f)
                override fun withoutPreampDb(): Float = prefs.getFloat("replaygain_preamp_without", 0f)
                override fun saveMode(mode: ReplayGainMode) {
                    prefs.edit().putString("replaygain_mode", mode.name).apply()
                }
            },
            isLocalOutputAuthority = { PlayerDependencies.outputAuthority.value.let {
                it.isLocal && it.endpointId == "local_phone"
            } },
            authorityLease = { PlayerDependencies.outputAuthority.value.let {
                SignalTruthLocalAuthorityLease(it.endpointId, it.isLocal, it.revision)
            } },
        )
    }
    var feedback by remember { mutableStateOf<ReplayGainQuickResult?>(null) }
    val active = PlayerDependencies.replayGainProcessor?.currentMode
    val enabled = active != null && outputAuthority.isLocal && outputAuthority.endpointId == "local_phone"
    val modes = listOf(ReplayGainMode.OFF, ReplayGainMode.TRACK,
        ReplayGainMode.ALBUM, ReplayGainMode.DYNAMIC)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Normalización ReplayGain", style = MaterialTheme.typography.titleSmall)
        Text("Selecciona el modo del procesador local; no modifica el DAC físico.",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        modes.chunked(2).forEach { rowModes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowModes.forEach { mode ->
                    val title = when (mode) {
                        ReplayGainMode.OFF -> "Desactivado"
                        ReplayGainMode.TRACK -> "Pista"
                        ReplayGainMode.ALBUM -> "Álbum"
                        ReplayGainMode.DYNAMIC -> "Dinámico"
                    }
                    OutlinedButton(
                        onClick = { feedback = coordinator.choose(SignalDestination.LOCAL, mode) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = enabled,
                    ) { Text(if (mode == active) "✓ $title" else title) }
                }
            }
        }
        if (!enabled) {
            Text("Ajustes deshabilitados: procesador local o autorización de salida no disponible.",
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        } else if (PlayerDependencies.replayGainProcessor?.isBypassed == true) {
            Text("Modo transparente: la selección se guarda, pero ReplayGain está omitido.",
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
        feedback?.let { result ->
            Text(result.message, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
    }
}
