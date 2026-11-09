package org.michimusic.mobile.playback

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
}

/** Local-only preset selection: never implicitly activates or rebinds a PEQ route. */
internal class SignalTruthHeadphoneCoordinator(
    private val engine: HeadphoneQuickEngine,
    private val authorityLease: (() -> SignalTruthLocalAuthorityLease?)? = null,
    private val hasLocalAuthority: () -> Boolean,
) {
    private fun current(lease: SignalTruthLocalAuthorityLease?): Boolean =
        hasLocalAuthority() && (authorityLease == null ||
            SignalTruthLocalAuthorityPolicy.unchanged(lease, runCatching { authorityLease?.invoke() }.getOrNull()))

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
