package org.michimusic.mobile.devices.aircat

/**
 * The complete local surface an operator UI needs to inspect and revoke Home
 * trust. It is a thin, read-mostly view over [AirCatHomeEnrollmentStore]: no
 * discovery, no network, no second identity and no grant. Every mutation is the
 * store's own optimistic compare-and-revoke, so a stale screen can never remove
 * a peer that was re-enrolled after the snapshot was taken.
 *
 * Extends [AirCatHomeAuthorityProvider] on purpose: the exact current record
 * (`anchorFor` / `anchorForSource`) is what lets the UI distinguish "the Home
 * changed under me" from "the record was already gone" instead of collapsing
 * every `false` into a generic failure.
 */
interface AirCatHomeTrustAdmin : AirCatHomeAuthorityProvider {

    /** Valid, currently authorised source bindings, as shown to an operator. */
    fun enrolledSources(): List<AirCatEnrolledHomeSource>

    /** Local Home roots with the exact source ids each one covers. */
    fun enrolledHomes(): List<AirCatEnrolledHomeAuthority>

    /** Optimistic single-source revocation; `false` means "not applied as asked". */
    fun revokeSourceIfUnchanged(expected: AirCatEnrolledHomeSource): Boolean

    /** Optimistic root revocation; `false` means "snapshot no longer matches". */
    fun revokeHomeIfUnchanged(expected: AirCatEnrolledHomeAuthority): Boolean
}
