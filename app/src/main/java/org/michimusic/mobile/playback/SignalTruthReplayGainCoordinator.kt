package org.michimusic.mobile.playback

import org.michimusic.player.ReplayGainMode

/** One-way control intent: never assume that a configured mode has processed an audio frame. */
internal enum class ReplayGainQuickStatus {
    CONFIGURED, UNAVAILABLE, WRONG_DESTINATION, OUTPUT_CHANGED, INVALID_PREFERENCES, REJECTED,
}

internal data class ReplayGainQuickResult(
    val status: ReplayGainQuickStatus,
    val mode: ReplayGainMode?,
    val message: String,
)

/** The engine is the existing ReplayGainAudioProcessor via PlayerDependencies. */
internal interface ReplayGainQuickEngine {
    fun currentMode(): ReplayGainMode?
    fun configure(mode: ReplayGainMode, withPreampDb: Float, withoutPreampDb: Float): Boolean
}

/** The store wraps Michi's *existing* michi_settings keys, not a second preference store. */
internal interface ReplayGainQuickStore {
    fun withPreampDb(): Float
    fun withoutPreampDb(): Float
    fun saveMode(mode: ReplayGainMode)
}

/** Historic adapter retained for existing regression tests only.
 * Production UI must use the serialized SettingsReplayGainGateway instead.
 */
@Deprecated("Legacy test compatibility only; use SettingsReplayGainGateway.shared(context) from production")
internal class SignalTruthReplayGainCoordinator(
    private val engine: ReplayGainQuickEngine,
    private val store: ReplayGainQuickStore,
    private val authorityLease: (() -> SignalTruthLocalAuthorityLease?)? = null,
    private val isLocalOutputAuthority: () -> Boolean,
) {
    private fun current(lease: SignalTruthLocalAuthorityLease?): Boolean =
        isLocalOutputAuthority() && (authorityLease == null ||
            SignalTruthLocalAuthorityPolicy.unchanged(lease, runCatching { authorityLease?.invoke() }.getOrNull()))

    fun choose(destination: SignalDestination, mode: ReplayGainMode): ReplayGainQuickResult {
        val lease = authorityLease?.let { runCatching { it() }.getOrNull() }
        if (destination != SignalDestination.LOCAL || !current(lease)) {
            return ReplayGainQuickResult(ReplayGainQuickStatus.WRONG_DESTINATION, null,
                "ReplayGain del teléfono no controla este destino.")
        }
        val before = runCatching { engine.currentMode() }.getOrNull()
            ?: return ReplayGainQuickResult(ReplayGainQuickStatus.UNAVAILABLE, null,
                "Procesador ReplayGain local no disponible; no se cambió ningún ajuste.")
        val with = runCatching { store.withPreampDb() }.getOrNull()
        val without = runCatching { store.withoutPreampDb() }.getOrNull()
        if (with == null || without == null || !with.isFinite() || !without.isFinite()) {
            return ReplayGainQuickResult(ReplayGainQuickStatus.INVALID_PREFERENCES, before,
                "Preamplificación inválida; corrige los valores desde Ajustes de audio.")
        }
        if (!current(lease)) return ReplayGainQuickResult(ReplayGainQuickStatus.OUTPUT_CHANGED,
            before, "La autoridad de salida cambió; no se envió el nuevo ajuste.")
        // Existing SettingsScreen uses the same processor and the same preference keys.
        // Never persist an unaccepted mode as though it were active in the engine.
        if (before != mode) {
            val accepted = runCatching { engine.configure(mode, with, without) }.getOrDefault(false)
            if (!accepted || runCatching { engine.currentMode() }.getOrNull() != mode) {
                return ReplayGainQuickResult(ReplayGainQuickStatus.REJECTED,
                    runCatching { engine.currentMode() }.getOrNull(),
                    "El procesador no confirmó el modo solicitado; no se guardó.")
            }
        }
        if (!current(lease)) return ReplayGainQuickResult(ReplayGainQuickStatus.OUTPUT_CHANGED,
            runCatching { engine.currentMode() }.getOrNull(),
            "La salida cambió durante el comando; no se guardó ni certificó el nuevo destino.")
        return runCatching {
            store.saveMode(mode)
            if (!current(lease)) ReplayGainQuickResult(ReplayGainQuickStatus.OUTPUT_CHANGED, mode,
                "El modo se guardó, pero la salida cambió durante la persistencia; no se atribuye al nuevo destino.")
            else ReplayGainQuickResult(ReplayGainQuickStatus.CONFIGURED, mode,
                "Modo configurado y guardado; no equivale a una prueba de muestras procesadas.")
        }.getOrElse {
            ReplayGainQuickResult(ReplayGainQuickStatus.REJECTED, mode,
                "Modo configurado en el motor, pero no se pudo guardar la preferencia.")
        }
    }
}
