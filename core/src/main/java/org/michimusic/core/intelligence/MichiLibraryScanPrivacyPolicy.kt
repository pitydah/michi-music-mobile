package org.michimusic.core.intelligence

/**
 * MI-03S: permission/ownership gate for reading ALREADY INDEXED music metadata.
 * No scanner, IO, MediaStore query, background timer or filesystem operation.
 * Only the existing LocalMediaRepository / UI7 genre authority may supply evidence.
 */
object MichiLibraryScanPrivacyPolicy {
    enum class Trigger { USER_REQUEST, EXISTING_LIBRARY_UPDATE, PERIODIC_TICK, EXTERNAL_SERVICE }
    enum class Action { DENY, USE_CACHED, CONSUME_AUTHORIZED_METADATA, REQUEST_EXISTING_LIBRARY_REFRESH }

    data class Evidence(
        val trigger: Trigger,
        val permissionGranted: Boolean,
        val ownerVerified: Boolean,
        val isLocalScope: Boolean,
        val hasCachedIndex: Boolean,
        val mediaStoreVersionMatches: Boolean,
        val mediaStoreGenerationChanged: Boolean,
    )

    fun decide(evidence: Evidence): Action {
        if (!evidence.permissionGranted || !evidence.ownerVerified || !evidence.isLocalScope ||
            evidence.trigger == Trigger.PERIODIC_TICK ||
            evidence.trigger == Trigger.EXTERNAL_SERVICE
        ) return Action.DENY
        if (!evidence.hasCachedIndex || !evidence.mediaStoreVersionMatches) {
            return if (evidence.trigger == Trigger.USER_REQUEST)
                Action.REQUEST_EXISTING_LIBRARY_REFRESH else Action.DENY
        }
        return if (evidence.mediaStoreGenerationChanged)
            Action.CONSUME_AUTHORIZED_METADATA else Action.USE_CACHED
    }
}
