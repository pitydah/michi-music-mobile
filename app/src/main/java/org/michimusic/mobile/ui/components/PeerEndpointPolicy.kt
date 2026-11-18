package org.michimusic.mobile.ui.components

/** Endpoint equality is for selecting already discovered metadata, NOT authentication.
 * A user-editable alias must never establish receiver identity or trust.
 */
fun sameDiscoveredEndpoint(inputHost: String, inputPort: Int, discoveredHost: String, discoveredPort: Int): Boolean =
    org.michimusic.mobile.devices.link.sameDiscoveredEndpoint(inputHost, inputPort, discoveredHost, discoveredPort)

/** A manually supplied endpoint with no matching discovery metadata has no verified type.
 * Fail closed for the physical-button UX, but never bypass domain authentication.
 */
fun allowLegacyReceiverButton(type: String?, matchedFromDiscovery: Boolean): Boolean =
    matchedFromDiscovery && !type.isNullOrBlank() && !isProvisionedMichiStream(type)

const val UNVERIFIED_RECEIVER_GUIDANCE =
    "No se pudo comprobar qué tipo de receptor responde en esta dirección. " +
    "Busca el dispositivo por Michi Link antes de elegir un método de vinculación. " +
    "Michi Stream utiliza Wi-Fi e identidad preconfigurados y no tiene botón de emparejamiento."

/** For visual presence, never equate two *different* reported Michi IDs just because
 * the network reused an IP/port. Discovery is still not authentication.
 */
fun matchesObservedPeer(
    knownId: String, knownHost: String, knownPort: Int,
    observedId: String, observedHost: String, observedPort: Int,
): Boolean = org.michimusic.mobile.devices.link.matchesObservedPeer(
    knownId, knownHost, knownPort, observedId, observedHost, observedPort,
)
