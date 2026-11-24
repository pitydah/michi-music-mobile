package org.michimusic.data.metadata

/**
 * ME-13 D: test-only observation points. Production uses [NoMetadataSaveCheckpoint];
 * no feature flag, global gate or source write depends on a hook.
 */
internal enum class MetadataSaveCheckpoint {
    AFTER_BACKUP_DURABLE,
    AFTER_STAGE_READY,
    BEFORE_MUTATING,
    AFTER_MUTATING_DURABLE,
    AFTER_PROVIDER_CLOSE,
    BEFORE_INDEX_CAS,
    AFTER_INDEX_CAS,
    BEFORE_TERMINAL_CLEANUP,
}

internal fun interface MetadataSaveCheckpointHook {
    suspend fun reach(point: MetadataSaveCheckpoint)
}

internal object NoMetadataSaveCheckpoint : MetadataSaveCheckpointHook {
    override suspend fun reach(point: MetadataSaveCheckpoint) = Unit
}
