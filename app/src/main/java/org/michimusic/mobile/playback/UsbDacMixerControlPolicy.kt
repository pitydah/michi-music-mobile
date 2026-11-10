package org.michimusic.mobile.playback

import android.media.AudioDeviceInfo
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.UsbMixerState

/** UI-only gate for Android USB mixer preferences; UsbDacManager repeats the guard. */
internal data class UsbMixerControlAvailability(
    val editable: Boolean,
    val explanation: String,
)

internal fun usbMixerControlAvailability(
    sdkInt: Int,
    route: AudioRouteStatus,
    mixer: UsbMixerState,
): UsbMixerControlAvailability {
    if (sdkInt < 34) return UsbMixerControlAvailability(
        false, "Android 14 o superior es necesario para solicitar frecuencias al mezclador USB.")
    val confirmed = (route as? AudioRouteStatus.Confirmed)?.device
        ?: return UsbMixerControlAvailability(
            false, "Reproduce por el DAC USB y espera una ruta observada por AudioTrack.")
    val usb = confirmed.androidType == AudioDeviceInfo.TYPE_USB_DEVICE ||
        confirmed.androidType == AudioDeviceInfo.TYPE_USB_HEADSET ||
        confirmed.androidType == AudioDeviceInfo.TYPE_USB_ACCESSORY
    if (!usb || mixer.deviceId != confirmed.androidDeviceId) {
        return UsbMixerControlAvailability(
            false, "Este dispositivo USB no es la salida confirmada; no se modificará su mezclador.")
    }
    if (mixer.supportedRates.isEmpty()) return UsbMixerControlAvailability(
        false, "Android no ofrece perfiles de mezclador normal ajustables para esta salida.")
    return UsbMixerControlAvailability(
        true, "Preferencia de Android disponible. No certifica frecuencia física ni bit-perfect.")
}
