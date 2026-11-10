package org.michimusic.mobile.playback

/** One immutable snapshot of Michi's existing playback output authority.
 * A local endpoint switched away and back MUST have a different revision.
 * This is a command safety guard, not evidence of DAC or PCM output.
 */
internal data class SignalTruthLocalAuthorityLease(
    val endpointId: String,
    val isLocal: Boolean,
    val revision: Long,
)

internal object SignalTruthLocalAuthorityPolicy {
    fun isPhone(lease: SignalTruthLocalAuthorityLease?): Boolean =
        lease != null && lease.isLocal && lease.endpointId == "local_phone" && lease.revision >= 0L

    /** Reject even an A -> B -> A switch during a long-running setting operation. */
    fun unchanged(before: SignalTruthLocalAuthorityLease?, after: SignalTruthLocalAuthorityLease?): Boolean =
        isPhone(before) && isPhone(after) && before == after
}
