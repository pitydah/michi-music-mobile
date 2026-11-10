package org.michimusic.mobile.playback

import android.media.AudioDeviceInfo
import org.michimusic.core.models.AudioProcessingMode
import org.michimusic.player.AudioRouteStatus

/** A profile is a user setting, not a physical headphone calibration. */
internal data class HeadphoneQuickSnapshot(
    val preset: String,
    val enabled: Boolean,
    val legacyBandsTruncated: Boolean,
    val bandsCount: Int,
)
internal enum class HeadphoneQuickStatus {
    CONFIGURED, DISABLED, UNAVAILABLE, WRONG_DESTINATION, OUTPUT_CHANGED, INVALID_PRESET, REJECTED,
}
internal data class HeadphoneQuickResult(val status: HeadphoneQuickStatus, val message: String)
internal interface HeadphoneQuickEngine {
    fun snapshot(): HeadphoneQuickSnapshot?
    fun selectPreset(preset: String): Boolean
    fun disableProfile(): Boolean
    /** A user may opt in only after Android has confirmed a headphone-capable route. */
    fun enableForRoute(deviceId: Int, observedAtMs: Long): Boolean = false
}

/** A visible enable action must never be offered for a speaker, unobserved route or bad preset. */
internal object SignalTruthHeadphoneRoutePolicy {
    fun eligible(route: AudioRouteStatus?, mode: AudioProcessingMode, snapshot: HeadphoneQuickSnapshot?): Boolean {
        if (mode != AudioProcessingMode.DSP || snapshot == null || snapshot.legacyBandsTruncated ||
            snapshot.bandsCount != 6) return false
        val confirmed = route as? AudioRouteStatus.Confirmed ?: return false
        return confirmed.observedAtMs > 0L && confirmed.device.androidDeviceId > 0 &&
            confirmed.device.androidType in setOf(
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
            )
    }
}

/** Local-only preset selection: never implicitly activates or rebinds a PEQ route. */
internal class SignalTruthHeadphoneCoordinator(
    private val engine: HeadphoneQuickEngine,
    private val authorityLease: (() -> SignalTruthLocalAuthorityLease?)? = null,
    private val currentRoute: (() -> AudioRouteStatus?)? = null,
    private val hasLocalAuthority: () -> Boolean,
) {
    private fun current(lease: SignalTruthLocalAuthorityLease?): Boolean =
        hasLocalAuthority() && (authorityLease == null ||
            SignalTruthLocalAuthorityPolicy.unchanged(lease, runCatching { authorityLease?.invoke() }.getOrNull()))

    /** No speaker, requested route or stale observation may activate personal PEQ.
     * This only enables a *configured* profile. A new PCM receipt proves application.
     */
    fun enableOnObservedRoute(destination: SignalDestination, mode: AudioProcessingMode): HeadphoneQuickResult {
        val lease = authorityLease?.let { runCatching { it() }.getOrNull() }
        if (destination != SignalDestination.LOCAL || !current(lease)) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.WRONG_DESTINATION,
                "El perfil solo se activa sobre una salida local autorizada.")
        }
        val snapshot = runCatching { engine.snapshot() }.getOrNull()
            ?: return HeadphoneQuickResult(HeadphoneQuickStatus.UNAVAILABLE,
                "Los perfiles de auriculares no están disponibles.")
        if (snapshot.legacyBandsTruncated || snapshot.bandsCount != 6) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
                "El perfil está incompleto. Selecciona un preset con seis filtros.")
        }
        if (mode != AudioProcessingMode.DSP) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
                "Selecciona modo DSP antes de activar el perfil.")
        }
        val route = runCatching { currentRoute?.invoke() }.getOrNull()
        val confirmed = route as? AudioRouteStatus.Confirmed
            ?: return HeadphoneQuickResult(HeadphoneQuickStatus.UNAVAILABLE,
                "Selecciona auriculares y espera a que Android confirme su ruta.")
        if (!SignalTruthHeadphoneRoutePolicy.eligible(confirmed, mode, snapshot)) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
                "Esta salida no es una ruta de auriculares compatible.")
        }
        if (!current(lease) || runCatching { currentRoute?.invoke() }.getOrNull() != confirmed) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.OUTPUT_CHANGED,
                "La ruta cambió. No se activó el perfil.")
        }
        val accepted = runCatching {
            engine.enableForRoute(confirmed.device.androidDeviceId, confirmed.observedAtMs)
        }.getOrDefault(false)
        if (!accepted) return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
            "Android no aceptó la vinculación del perfil a esta ruta.")
        if (!current(lease) || runCatching { currentRoute?.invoke() }.getOrNull() != confirmed) {
            runCatching { engine.disableProfile() }
            return HeadphoneQuickResult(HeadphoneQuickStatus.OUTPUT_CHANGED,
                "La ruta cambió: el perfil se desactivó por seguridad.")
        }
        if (runCatching { engine.snapshot()?.enabled }.getOrNull() != true) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
                "El perfil no confirmó que quedó habilitado.")
        }
        return HeadphoneQuickResult(HeadphoneQuickStatus.CONFIGURED,
            "Perfil habilitado para esta ruta. Se confirmará al procesar nuevas muestras PCM.")
    }

    fun choosePreset(destination: SignalDestination, preset: String): HeadphoneQuickResult {
        val lease = authorityLease?.let { runCatching { it() }.getOrNull() }
        if (destination != SignalDestination.LOCAL || !current(lease)) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.WRONG_DESTINATION,
                "El perfil local no modifica el receptor remoto ni el transporte RTP.")
        }
        if (preset !in setOf("Neutral", "Warm", "Voice")) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.INVALID_PRESET,
                "Preset no reconocido; conserva el perfil anterior.")
        }
        val previous = runCatching { engine.snapshot() }.getOrNull()
            ?: return HeadphoneQuickResult(HeadphoneQuickStatus.UNAVAILABLE,
                "HeadphoneProfileStore no disponible.")
        if (!current(lease)) return HeadphoneQuickResult(HeadphoneQuickStatus.OUTPUT_CHANGED,
            "El destino cambió antes de seleccionar el perfil.")
        if (previous.preset != preset || previous.legacyBandsTruncated) {
            if (!runCatching { engine.selectPreset(preset) }.getOrDefault(false)) {
                return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
                    "No se pudo guardar el preset de auriculares.")
            }
        }
        val readback = runCatching { engine.snapshot() }.getOrNull()
        if (!current(lease)) return HeadphoneQuickResult(HeadphoneQuickStatus.OUTPUT_CHANGED,
            "La salida cambió durante la selección; el perfil no se declara aplicado.")
        if (readback?.preset != preset || readback.bandsCount != 6 || readback.legacyBandsTruncated) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
                "El perfil no confirmo sus seis filtros; no se declara aplicado.")
        }
        return HeadphoneQuickResult(HeadphoneQuickStatus.CONFIGURED,
            "Perfil $preset guardado; la aplicacion depende de la ruta autorizada y de nuevas muestras PEQ.")
    }

    /** Disabling must work even after unplugging; never enable a disconnected route. */
    fun disable(destination: SignalDestination): HeadphoneQuickResult {
        val lease = authorityLease?.let { runCatching { it() }.getOrNull() }
        if (destination != SignalDestination.LOCAL || !current(lease)) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.WRONG_DESTINATION,
                "Sin autoridad de salida local.")
        }
        val old = runCatching { engine.snapshot() }.getOrNull()
            ?: return HeadphoneQuickResult(HeadphoneQuickStatus.UNAVAILABLE,
                "HeadphoneProfileStore no disponible.")
        if (!current(lease)) return HeadphoneQuickResult(HeadphoneQuickStatus.OUTPUT_CHANGED,
            "La salida cambió antes de desactivar el perfil.")
        if (old.enabled && !runCatching { engine.disableProfile() }.getOrDefault(false)) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
                "No se pudo desactivar el perfil.")
        }
        if (!current(lease)) return HeadphoneQuickResult(HeadphoneQuickStatus.OUTPUT_CHANGED,
            "La salida cambió al desactivar; no se certifica el destino nuevo.")
        val disabled = runCatching { engine.snapshot()?.enabled }.getOrNull() == false
        if (!current(lease)) return HeadphoneQuickResult(HeadphoneQuickStatus.OUTPUT_CHANGED,
            "La salida cambió al comprobar la desactivación; no se acredita el destino nuevo.")
        if (!disabled) {
            return HeadphoneQuickResult(HeadphoneQuickStatus.REJECTED,
                "La desactivacion no fue confirmada por el store.")
        }
        return HeadphoneQuickResult(HeadphoneQuickStatus.DISABLED,
            "Perfil desactivado y configuracion conservada; no es prueba de muestra procesada.")
    }
}
