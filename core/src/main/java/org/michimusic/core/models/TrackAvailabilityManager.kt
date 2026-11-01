package org.michimusic.core.models

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Gestor reactivo centralizado de disponibilidad de pistas físicas en el dispositivo.
 * Proporciona verificación real a través de Context / ContentResolver y emite eventos
 * de invalidación cuando se descargan nuevas pistas o se actualiza la biblioteca.
 */
class TrackAvailabilityManager(
    private val context: Context? = null
) {
    private val _availabilityInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val availabilityInvalidations: SharedFlow<Unit> = _availabilityInvalidations.asSharedFlow()

    fun isTrackAvailable(filepath: String?): Boolean {
        return TrackAvailabilityChecker.isTrackAvailable(filepath, context)
    }

    fun isTrackDownloaded(track: Track): Boolean {
        return isTrackAvailable(track.filepath)
    }

    fun notifyAvailabilityChanged() {
        _availabilityInvalidations.tryEmit(Unit)
    }
}
