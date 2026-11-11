package org.michimusic.mobile.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.michimusic.player.ReplayGainMode

/** Only one field is persisted per gesture, so Signal Truth's concurrent
 * preference changes to other keys cannot be overwritten with stale values.
 */
internal data class SettingsReplayGainSnapshot(
    val mode: ReplayGainMode,
    val withTagsDb: Float,
    val withoutTagsDb: Float,
)

internal sealed interface SettingsReplayGainEdit {
    data class Mode(val value: ReplayGainMode) : SettingsReplayGainEdit
    data class WithTags(val value: Float) : SettingsReplayGainEdit
    data class WithoutTags(val value: Float) : SettingsReplayGainEdit
}

internal fun SettingsReplayGainEdit.applyTo(before: SettingsReplayGainSnapshot): SettingsReplayGainSnapshot = when (this) {
    is SettingsReplayGainEdit.Mode -> before.copy(mode = value)
    is SettingsReplayGainEdit.WithTags -> before.copy(withTagsDb = value)
    is SettingsReplayGainEdit.WithoutTags -> before.copy(withoutTagsDb = value)
}

internal fun SettingsReplayGainEdit.previousValue(before: SettingsReplayGainSnapshot): SettingsReplayGainEdit = when (this) {
    is SettingsReplayGainEdit.Mode -> SettingsReplayGainEdit.Mode(before.mode)
    is SettingsReplayGainEdit.WithTags -> SettingsReplayGainEdit.WithTags(before.withTagsDb)
    is SettingsReplayGainEdit.WithoutTags -> SettingsReplayGainEdit.WithoutTags(before.withoutTagsDb)
}

internal fun SettingsReplayGainSnapshot.valid(): Boolean =
    withTagsDb.isFinite() && withoutTagsDb.isFinite() &&
        withTagsDb in -15f..15f && withoutTagsDb in -15f..15f

internal enum class SettingsReplayGainSaveStatus {
    CONFIGURED, SAVED_FOR_LOCAL, REJECTED, ROLLED_BACK, STATE_UNCERTAIN, EXTERNAL_CHANGE,
}

internal data class SettingsReplayGainReceipt(
    val status: SettingsReplayGainSaveStatus,
    val snapshot: SettingsReplayGainSnapshot?,
) {
    val success: Boolean get() = status == SettingsReplayGainSaveStatus.CONFIGURED ||
        status == SettingsReplayGainSaveStatus.SAVED_FOR_LOCAL
}

/** Adapter methods are written on their correct dispatcher by the gateway. */
internal interface SettingsReplayGainTransactionPort {
    suspend fun read(): SettingsReplayGainSnapshot
    suspend fun persist(edit: SettingsReplayGainEdit): Boolean
    /** True means processor configured, null means processor unavailable/remote;
     * false means an available processor rejected the request.
     */
    suspend fun configure(value: SettingsReplayGainSnapshot): Boolean?
}

/** Serialized transactional save with an explicit recovery contract.
 * A failed preference write cannot leave a newly configured engine in memory;
 * on a processor rejection, rollback only the field actually edited.
 */
internal class SettingsReplayGainTransaction(
    private val port: SettingsReplayGainTransactionPort,
) {
    private val mutex = Mutex()

    suspend fun save(edit: SettingsReplayGainEdit): SettingsReplayGainReceipt = mutex.withLock {
        val before = try { port.read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            return@withLock SettingsReplayGainReceipt(SettingsReplayGainSaveStatus.STATE_UNCERTAIN, null)
        }
        val desired = edit.applyTo(before)
        if (!desired.valid()) return@withLock SettingsReplayGainReceipt(
            SettingsReplayGainSaveStatus.REJECTED, before)
        if (desired == before) return@withLock SettingsReplayGainReceipt(
            SettingsReplayGainSaveStatus.SAVED_FOR_LOCAL, before)
        val written = try { port.persist(edit) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        if (!written) {
            // Android can update the in-memory preference even when commit fails.
            // Attempt to restore only our field, without touching other controls.
            val observed = runCatching { port.read() }.getOrNull()
            if (observed == before) return@withLock SettingsReplayGainReceipt(
                SettingsReplayGainSaveStatus.REJECTED, before)
            val stillOurValue = observed != null && when (edit) {
                is SettingsReplayGainEdit.Mode -> observed.mode == desired.mode
                is SettingsReplayGainEdit.WithTags -> observed.withTagsDb == desired.withTagsDb
                is SettingsReplayGainEdit.WithoutTags -> observed.withoutTagsDb == desired.withoutTagsDb
            }
            if (stillOurValue) {
                val reverted = try { port.persist(edit.previousValue(before)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                val current = runCatching { port.read() }.getOrNull()
                if (reverted && current == before) return@withLock SettingsReplayGainReceipt(
                    SettingsReplayGainSaveStatus.REJECTED, current)
                return@withLock SettingsReplayGainReceipt(
                    SettingsReplayGainSaveStatus.STATE_UNCERTAIN, current)
            }
            return@withLock SettingsReplayGainReceipt(
                SettingsReplayGainSaveStatus.STATE_UNCERTAIN, observed)
        }
        val stored = runCatching { port.read() }.getOrNull()
            ?: return@withLock SettingsReplayGainReceipt(
                SettingsReplayGainSaveStatus.STATE_UNCERTAIN, null)
        // Another surface may have changed this field in the meantime.
        val sameEditedField = when (edit) {
            is SettingsReplayGainEdit.Mode -> stored.mode == desired.mode
            is SettingsReplayGainEdit.WithTags -> stored.withTagsDb == desired.withTagsDb
            is SettingsReplayGainEdit.WithoutTags -> stored.withoutTagsDb == desired.withoutTagsDb
        }
        if (!sameEditedField) return@withLock SettingsReplayGainReceipt(
            SettingsReplayGainSaveStatus.EXTERNAL_CHANGE, stored)

        val configured = try { port.configure(stored) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        if (configured == true || configured == null) return@withLock SettingsReplayGainReceipt(
            if (configured == true) SettingsReplayGainSaveStatus.CONFIGURED
                else SettingsReplayGainSaveStatus.SAVED_FOR_LOCAL, stored)

        // Processor rejected an already persisted change. Revert only the field
        // we own, provided nobody else replaced its value since the commit.
        val latest = runCatching { port.read() }.getOrNull()
        val stillOurs = latest != null && when (edit) {
            is SettingsReplayGainEdit.Mode -> latest.mode == desired.mode
            is SettingsReplayGainEdit.WithTags -> latest.withTagsDb == desired.withTagsDb
            is SettingsReplayGainEdit.WithoutTags -> latest.withoutTagsDb == desired.withoutTagsDb
        }
        if (!stillOurs) return@withLock SettingsReplayGainReceipt(
            SettingsReplayGainSaveStatus.EXTERNAL_CHANGE, latest)
        val restored = try { port.persist(edit.previousValue(before)) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        val recovered = runCatching { port.read() }.getOrNull()
        val fieldRestored = recovered != null && when (edit) {
            is SettingsReplayGainEdit.Mode -> recovered.mode == before.mode
            is SettingsReplayGainEdit.WithTags -> recovered.withTagsDb == before.withTagsDb
            is SettingsReplayGainEdit.WithoutTags -> recovered.withoutTagsDb == before.withoutTagsDb
        }
        if (restored && fieldRestored && recovered != null) {
            // Only declare a successful rollback when BOTH storage and the
            // existing processor confirm restoration of the preceding state.
            val engineRestored = runCatching { port.configure(recovered) }.getOrNull()
            return@withLock SettingsReplayGainReceipt(
                if (engineRestored == true) SettingsReplayGainSaveStatus.ROLLED_BACK
                    else SettingsReplayGainSaveStatus.STATE_UNCERTAIN,
                recovered)
        }
        SettingsReplayGainReceipt(SettingsReplayGainSaveStatus.STATE_UNCERTAIN, recovered)
    }
}

/** A configured mode is not a receipt of audio samples or DAC processing. */
internal fun settingsReplayGainTruth(
    mode: ReplayGainMode,
    localOutput: Boolean,
    processorAvailable: Boolean,
    transparent: Boolean,
): String = when {
    mode == ReplayGainMode.OFF -> "ReplayGain desactivado en las preferencias."
    !localOutput -> "Guardado para el teléfono; no modifica la salida remota."
    !processorAvailable -> "Guardado; procesador ReplayGain local no disponible."
    transparent -> "Modo transparente: preferencia guardada, efecto omitido."
    else -> "Modo configurado; no certifica muestras procesadas ni la salida del DAC."
}
