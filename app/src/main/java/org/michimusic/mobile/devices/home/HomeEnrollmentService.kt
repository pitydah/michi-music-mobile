package org.michimusic.mobile.devices.home

import org.michimusic.link.home.HomeMembershipDto
import org.michimusic.mobile.devices.aircat.AirCatHomeAnchor

/**
 * MIM-05.5: the enrollment surface the trust UI may call. The production
 * implementation is [HomeEnrollmentCoordinator]; tests provide a fake without
 * opening a network or the Keystore.
 */
interface HomeEnrollmentService {
    suspend fun requestEnrollment(
        anchor: AirCatHomeAnchor,
        deviceName: String,
        requestedRoles: List<String> = HomeEnrollmentCoordinator.DEFAULT_MOBILE_ROLES,
    ): HomeEnrollmentCoordinator.Outcome

    suspend fun refreshEnrollment(anchor: AirCatHomeAnchor): HomeEnrollmentCoordinator.Outcome

    suspend fun awaitApproval(
        anchor: AirCatHomeAnchor,
        maxAttempts: Int = 150,
        intervalMillis: Long = 2_000,
    ): HomeEnrollmentCoordinator.Outcome

    fun storedMembership(anchor: AirCatHomeAnchor): HomeMembershipDto?

    fun pendingRequest(anchor: AirCatHomeAnchor): HomeMembershipStore.PendingEnrollment?
}
