package org.michimusic.player

/**
 * Pure policy decisions. No Android service or AudioTrack state is inferred here:
 * callers supply an observation from a *playing* track.
 */
internal fun classifyObservedAudioRoute(
    requested: OutputDescriptor?,
    actual: OutputDescriptor,
    observedAtMs: Long,
): AudioRouteStatus = if (requested == null || requested.androidDeviceId == actual.androidDeviceId) {
    AudioRouteStatus.Confirmed(actual, observedAtMs)
} else {
    AudioRouteStatus.RoutedElsewhere(requested, actual, observedAtMs)
}

/**
 * Re-enumeration is not a new user request. A scanner must not revoke
 * CONFIRMED/RoutedElsewhere/LastObserved evidence just because USB emits a callback.
 * The exact descriptor is compared, so an ID reused with different metadata is
 * treated as a new request rather than assumed to be the same device.
 */
internal fun sameRequestedRoute(previous: OutputDescriptor?, current: OutputDescriptor?): Boolean =
    previous == current

/** The effective route, not the attached/selected USB list, drives AUTO mode. */
internal fun currentlyObservedOutput(route: AudioRouteStatus?): OutputDescriptor? = when (route) {
    is AudioRouteStatus.Confirmed -> route.device
    is AudioRouteStatus.RoutedElsewhere -> route.actual
    else -> null // Requested, LastObserved, Unknown and Failed are not live evidence.
}

/**
 * A user-protected USB request routed to another output must not silently
 * continue on the handset speaker or another DAC. Kept pure for JVM testing.
 */
internal fun usbRouteMismatchRequiresPause(
    route: AudioRouteStatus,
    usbTypes: Set<Int>,
): Boolean = route is AudioRouteStatus.RoutedElsewhere && route.requested.androidType in usbTypes

/** Losing B must be detected even while A stays connected. */
internal fun missingSelectedUsbOutput(
    explicitSelection: Int?,
    automaticUsbId: Int?,
    connectedOutputIds: Set<Int>,
): Int? {
    val watched = explicitSelection ?: automaticUsbId
    return watched?.takeIf { it !in connectedOutputIds }
}
