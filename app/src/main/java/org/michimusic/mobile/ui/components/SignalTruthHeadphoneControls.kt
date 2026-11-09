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
import org.michimusic.mobile.playback.HeadphoneQuickEngine
import org.michimusic.mobile.playback.HeadphoneQuickResult
import org.michimusic.mobile.playback.HeadphoneQuickSnapshot
import org.michimusic.mobile.playback.SignalDestination
import org.michimusic.mobile.playback.SignalTruthHeadphoneCoordinator
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.playback.SignalTruthLocalAuthorityLease
import org.michimusic.player.PlayerDependencies

/** Presets only. Enabling or binding PEQ to a route stays with Headphone Studio. */
@Composable
internal fun SignalTruthHeadphoneControls() {
    val store = PlayerDependencies.headphoneProfileStore
    val outputAuthority by PlayerDependencies.outputAuthority.collectAsStateWithLifecycle()
    val state by (store?.state?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(org.michimusic.player.HeadphoneProfileState())
    })
    val coordinator = remember(store) {
        SignalTruthHeadphoneCoordinator(object : HeadphoneQuickEngine {
            override fun snapshot(): HeadphoneQuickSnapshot? = store?.state?.value?.let {
                HeadphoneQuickSnapshot(it.preset, it.enabled, it.legacyBandsTruncated, it.bands.size)
            }
            override fun selectPreset(preset: String): Boolean {
                val target = store ?: return false
                target.selectPreset(preset)
                return target.state.value.preset == preset
            }
            override fun disableProfile(): Boolean {
                val target = store ?: return false
                target.enable(false)
                return !target.state.value.enabled
            }
        }, hasLocalAuthority = { PlayerDependencies.outputAuthority.value.let {
            it.isLocal && it.endpointId == "local_phone"
        } }, authorityLease = { PlayerDependencies.outputAuthority.value.let {
                SignalTruthLocalAuthorityLease(it.endpointId, it.isLocal, it.revision)
            } })
    }
    var feedback by remember { mutableStateOf<HeadphoneQuickResult?>(null) }
    val enabled = store != null && outputAuthority.isLocal && outputAuthority.endpointId == "local_phone"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Perfil de auriculares", style = MaterialTheme.typography.titleSmall)
        Text("${state.headphoneName} · ${if (state.enabled) "Habilitado en preferencias; ruta por verificar" else "Desactivado"}",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        Text("Seleccionar un preset no activa auriculares, ni certifica PEQ aplicado.",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("Neutral", "Warm", "Voice").forEach { preset ->
                OutlinedButton(
                    onClick = { feedback = coordinator.choosePreset(SignalDestination.LOCAL, preset) },
                    enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text((if (state.preset == preset) "✓ " else "") + preset) }
            }
        }
        if (state.enabled) OutlinedButton(
            enabled = enabled, onClick = { feedback = coordinator.disable(SignalDestination.LOCAL) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Desactivar PEQ") }
        if (state.legacyBandsTruncated) Text("Perfil antiguo incompleto; elige uno nuevo para recuperar seis filtros.",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        feedback?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant) }
    }
}
