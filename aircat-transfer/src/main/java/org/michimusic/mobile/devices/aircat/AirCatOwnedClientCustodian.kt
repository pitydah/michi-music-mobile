package org.michimusic.mobile.devices.aircat

import org.michimusic.link.LinkClient

/**
 * Explicit ownership of temporary Link clients retained by a paused/retriable
 * AirCat transfer. Borrowed ConnectionManager clients must never be registered.
 * Does not grant transfer authorization or create an additional identity.
 */
interface AirCatOwnedClientCustodian {
    fun adoptOwnedClient(transferId: String, client: LinkClient)
    fun releaseIfNotRetained(transferId: String)
}
