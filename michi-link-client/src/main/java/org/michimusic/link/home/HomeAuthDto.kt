package org.michimusic.link.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MIM-05/MIM-06 wire contracts. Field names and nullability mirror the pinned
 * Michi Link JSON Schemas at `8dad0fb5`; no legacy aliases are accepted here.
 */

@Serializable
data class HomeMembershipDto(
    val version: Int = 1,
    @SerialName("home_id") val homeId: String,
    @SerialName("device_michi_id") val deviceMichiId: String,
    @SerialName("device_public_key") val devicePublicKey: String,
    @SerialName("device_type") val deviceType: String,
    val roles: List<String> = emptyList(),
    @SerialName("issued_at") val issuedAt: String,
    val serial: Long,
    val signature: String,
)

@Serializable
data class HomeEnrollmentChallengeRequestDto(
    val purpose: String = "home_enrollment",
    @SerialName("home_id") val homeId: String,
    @SerialName("client_michi_id") val clientMichiId: String,
    @SerialName("client_public_key") val clientPublicKey: String,
)

@Serializable
data class HomeEnrollmentChallengeResponseDto(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("challenge_nonce") val challengeNonce: String,
    @SerialName("home_id") val homeId: String,
    @SerialName("server_michi_id") val serverMichiId: String,
    @SerialName("server_public_key") val serverPublicKey: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class HomeEnrollmentRequestDto(
    @SerialName("home_id") val homeId: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("client_michi_id") val clientMichiId: String,
    @SerialName("client_public_key") val clientPublicKey: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_type") val deviceType: String,
    @SerialName("requested_roles") val requestedRoles: List<String>,
    @SerialName("client_signature") val clientSignature: String,
)

@Serializable
data class HomeEnrollmentRequestResponseDto(
    @SerialName("request_id") val requestId: String,
    val status: String,
    @SerialName("home_id") val homeId: String,
    @SerialName("client_michi_id") val clientMichiId: String,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("claim_token") val claimToken: String = "",
)

@Serializable
data class HomeEnrollmentStatusResponseDto(
    @SerialName("request_id") val requestId: String,
    val status: String,
    @SerialName("home_id") val homeId: String,
    @SerialName("home_root_public_key") val homeRootPublicKey: String? = null,
    val membership: HomeMembershipDto? = null,
)

@Serializable
data class HomeDeviceAuthChallengeRequestDto(
    @SerialName("client_michi_id") val clientMichiId: String,
    @SerialName("client_public_key") val clientPublicKey: String,
    @SerialName("home_id") val homeId: String,
)

@Serializable
data class HomeDeviceAuthChallengeResponseDto(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("challenge_nonce") val challengeNonce: String,
    @SerialName("server_michi_id") val serverMichiId: String,
    @SerialName("server_public_key") val serverPublicKey: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class HomeAuthSessionRequestDto(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("client_michi_id") val clientMichiId: String,
    val membership: HomeMembershipDto,
    @SerialName("client_signature") val clientSignature: String,
)

@Serializable
data class HomeAuthSessionResponseDto(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("server_michi_id") val serverMichiId: String,
    @SerialName("server_membership") val serverMembership: HomeMembershipDto,
    @SerialName("server_signature") val serverSignature: String,
)

/** Enrollment lifecycle as observed by Mobile. `ENROLLED_OFFLINE` is not online. */
enum class HomeEnrollmentPhase {
    NOT_ENROLLED,
    ENROLLMENT_PENDING,
    ENROLLED_OFFLINE,
    AUTHENTICATED_ONLINE,
    REJECTED,
    REVOKED,
    ERROR,
}
