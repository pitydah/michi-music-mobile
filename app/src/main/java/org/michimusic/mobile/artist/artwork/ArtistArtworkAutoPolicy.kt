package org.michimusic.mobile.artist.artwork

/**
 * Pure, offline eligibility decision. Only an already persisted artist association may
 * enter automatic artwork recovery. This class performs no queries, writes or scheduling.
 * Its caller must hydrate selections from Room and verify READY bytes before invoking it.
 */
internal object ArtistArtworkAutoPolicy {
    fun evaluate(
        anchor: ArtistIdentityAnchor?,
        selections: Collection<ArtistArtworkSelection>,
        conditions: AutoArtworkConditions,
        manualSlots: Set<ArtworkSlot> = emptySet(),
    ): AutoArtworkDecision {
        if (!conditions.enabled) return AutoArtworkDecision.Blocked(AutoArtworkBlock.DISABLED)
        if (conditions.paused) return AutoArtworkDecision.Blocked(AutoArtworkBlock.PAUSED)
        if (anchor == null || anchor.libraryId.isBlank() || anchor.creditKey.isBlank() ||
            anchor.albumGroupKey.isBlank() || anchor.confirmedMbid.isBlank()
        ) return AutoArtworkDecision.Blocked(AutoArtworkBlock.IDENTITY_NOT_CONFIRMED)
        if (conditions.associationNeedsConfirmation) {
            return AutoArtworkDecision.Blocked(AutoArtworkBlock.ASSOCIATION_NEEDS_CONFIRMATION)
        }
        val qid = anchor.verifiedQid
        if (qid == null || !validWikidataQid(qid)) {
            return AutoArtworkDecision.Blocked(AutoArtworkBlock.QID_NOT_VERIFIED)
        }

        // An explicit manual choice is never automatically replaced, even if its
        // local bytes have become unreadable. The user must decide how to repair it.
        val protectedSlots = selections.asSequence()
            .filter { it.manuallyChosen ||
                (it.confirmedMbid.equals(anchor.confirmedMbid, ignoreCase = true) &&
                    it.status == ArtworkLoadStatus.READY && !it.localImagePath.isNullOrBlank()) }
            .map { it.slot }
            .toSet() + manualSlots
        val missing = ArtworkSlot.entries.toSet() - protectedSlots
        if (missing.isEmpty()) return AutoArtworkDecision.Blocked(AutoArtworkBlock.NOTHING_TO_FETCH)

        if (conditions.nextRetryAtEpochMs?.let { it > conditions.nowEpochMs } == true) {
            return AutoArtworkDecision.Blocked(AutoArtworkBlock.COOLDOWN)
        }
        if (!conditions.networkAvailable ||
            (conditions.unmeteredOnly && !conditions.onUnmeteredNetwork)
        ) return AutoArtworkDecision.Blocked(AutoArtworkBlock.NETWORK_UNAVAILABLE)
        if (!conditions.batteryNotLow) return AutoArtworkDecision.Blocked(AutoArtworkBlock.BATTERY_LOW)
        if (!conditions.storageNotLow) return AutoArtworkDecision.Blocked(AutoArtworkBlock.STORAGE_LOW)

        return AutoArtworkDecision.Eligible(missing)
    }
}

internal data class AutoArtworkConditions(
    val enabled: Boolean,
    val paused: Boolean,
    val associationNeedsConfirmation: Boolean,
    val networkAvailable: Boolean,
    val unmeteredOnly: Boolean,
    val onUnmeteredNetwork: Boolean,
    val batteryNotLow: Boolean,
    val storageNotLow: Boolean,
    val nowEpochMs: Long,
    val nextRetryAtEpochMs: Long? = null,
)

internal enum class AutoArtworkBlock {
    DISABLED,
    PAUSED,
    IDENTITY_NOT_CONFIRMED,
    ASSOCIATION_NEEDS_CONFIRMATION,
    QID_NOT_VERIFIED,
    NOTHING_TO_FETCH,
    COOLDOWN,
    NETWORK_UNAVAILABLE,
    BATTERY_LOW,
    STORAGE_LOW,
}

internal sealed interface AutoArtworkDecision {
    data class Eligible(val missingSlots: Set<ArtworkSlot>) : AutoArtworkDecision
    data class Blocked(val reason: AutoArtworkBlock) : AutoArtworkDecision
}
