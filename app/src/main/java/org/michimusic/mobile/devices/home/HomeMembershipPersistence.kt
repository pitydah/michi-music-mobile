package org.michimusic.mobile.devices.home

/**
 * MIM-05/06 storage seam for the enrollment coordinator. It exists so the state
 * machine can be tested without Android Keystore, not as a second authority:
 * the production implementation is [HomeMembershipStore].
 */
interface HomeMembershipPersistence {
    fun savePending(pending: HomeMembershipStore.PendingEnrollment): Boolean
    fun loadPending(homeId: String): HomeMembershipStore.PendingEnrollment?
    fun clearPending(homeId: String): Boolean
    fun saveMembership(record: HomeMembershipStore.MembershipRecord): Boolean
    fun loadMembership(homeId: String): HomeMembershipStore.MembershipRecord?
    fun clearMembership(homeId: String): Boolean
}
