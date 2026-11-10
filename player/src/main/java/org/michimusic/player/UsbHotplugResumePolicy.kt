package org.michimusic.player

import android.media.AudioDeviceInfo
import androidx.media3.common.Player

/** USB detach must not silently start the phone speaker or a second DAC.
 * Only a fresh, explicit user play request is allowed to release the recovery latch.
 * This policy does not claim any route is confirmed until AudioTrack observes it.
 */
internal object UsbHotplugResumePolicy {
    // Media3 USER_REQUEST includes application calls; it does not prove a tap.
    // Consent must originate from the app's explicit play control instead.
    fun blockUnapprovedPlay(recoveryRequired: Boolean, playWhenReady: Boolean): Boolean =
        recoveryRequired && playWhenReady

    fun nativeSpeakerMatches(selectedId: Int?, nativeId: Int?, nativeType: Int?): Boolean =
        selectedId != null && selectedId == nativeId && nativeType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    fun recoveredSpeakerMismatch(route: AudioRouteStatus, recoveredId: Int?): Boolean =
        recoveredId != null && route is AudioRouteStatus.RoutedElsewhere &&
            route.requested.androidDeviceId == recoveredId &&
            route.actual.androidDeviceId != recoveredId

    fun safePhoneSpeaker(devices: List<OutputDescriptor>): OutputDescriptor? =
        devices.firstOrNull { it.androidType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    fun needsPrepare(playbackState: Int, itemCount: Int): Boolean =
        playbackState == Player.STATE_IDLE && itemCount > 0

    /** Audio-sink/processor failures do not establish that the song is corrupt.
     * In Media3, 5xxx denotes the audio-rendering error family.
     */
    fun isOutputFailure(errorCode: Int): Boolean = errorCode in 5000..5999
}
