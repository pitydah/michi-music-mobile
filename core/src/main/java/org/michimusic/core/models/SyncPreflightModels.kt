package org.michimusic.core.models

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class SyncScope {
    FULL_LIBRARY,
    PLAYLISTS,
    ALBUMS,
    ARTISTS,
    TRACKS
}

@Serializable
data class SyncSelection(
    val scope: SyncScope = SyncScope.FULL_LIBRARY,
    val playlistIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val artistIds: Set<String> = emptySet(),
    val trackIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = when (scope) {
            SyncScope.FULL_LIBRARY -> false
            SyncScope.PLAYLISTS -> playlistIds.isEmpty()
            SyncScope.ALBUMS -> albumIds.isEmpty()
            SyncScope.ARTISTS -> artistIds.isEmpty()
            SyncScope.TRACKS -> trackIds.isEmpty()
        }
}

@Serializable
enum class DeliveryProfile(val label: String, val description: String) {
    ORIGINAL("Calidad Original", "Audio tal como reside en el servidor sin transcodificación"),
    BALANCED("Balanceado", "Transcodificación en servidor para ahorrar espacio con alta fidelidad"),
    LOW_STORAGE("Almacenamiento Reducido", "Máxima compresión en servidor para dispositivos con poco espacio"),
}

@Serializable
data class ProfileAvailability(
    val profile: DeliveryProfile,
    val isAvailable: Boolean,
    val reasonIfNot: String? = null,
    val codecDescription: String = "",
)

@Serializable
data class DeliveryChoice(
    val profile: DeliveryProfile = DeliveryProfile.ORIGINAL,
    val codec: String = "original",
    val maxBitrateKbps: Int? = null,
    val isServerTranscoded: Boolean = false,
)

@Serializable
enum class SyncItemAction {
    DOWNLOAD,
    SKIP_ALREADY_EXISTS,
    RESUME_PARTIAL,
    REPLACE_BETTER_QUALITY,
    BLOCKED_UNSUPPORTED
}

@Serializable
data class SyncItemDecision(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val action: SyncItemAction,
    val sourceSizeBytes: Long,
    val estimatedDownloadBytes: Long,
    val localPath: String? = null,
    val reason: String? = null,
)

@Serializable
enum class IssueSeverity {
    WARNING,
    BLOCKING
}

@Serializable
data class SyncPreflightIssue(
    val code: String,
    val message: String,
    val severity: IssueSeverity,
)

@Serializable
enum class SyncPreflightStatus {
    READY,
    BLOCKED_NO_STORAGE,
    BLOCKED_EMPTY_SELECTION,
    BLOCKED_UNSUPPORTED_FORMAT,
    BLOCKED_SERVER_OFFLINE,
}

@Serializable
enum class TransferLifecycleState {
    QUEUED,
    PREPARING,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    REGISTERING,
    COMPLETED,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED,
    RETRY_PENDING,
}

@Serializable
enum class StoragePreflightStatus {
    AVAILABLE,
    INSUFFICIENT,
    UNKNOWN,
    NOT_WRITABLE,
}

@Serializable
data class SyncPlan(
    val planId: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val endpointId: String,
    val endpointName: String,
    val selection: SyncSelection,
    val deliveryChoice: DeliveryChoice,
    val items: List<SyncItemDecision>,
    val totalSelectedTracks: Int,
    val tracksToDownloadCount: Int,
    val tracksSkippedCount: Int,
    val tracksToResumeCount: Int,
    val totalDownloadBytes: Long,
    val availableStorageBytes: Long,
    val safetyBufferBytes: Long,
    val remainingStorageAfterSyncBytes: Long,
    val issues: List<SyncPreflightIssue> = emptyList(),
    val status: SyncPreflightStatus = SyncPreflightStatus.READY,
    val manifestVersion: Int = 1,
    val manifestChecksum: String = "",
) {
    val isExecutable: Boolean
        get() = status == SyncPreflightStatus.READY &&
            issues.none { it.severity == IssueSeverity.BLOCKING } &&
            totalSelectedTracks > 0
}

@Serializable
data class ApprovedSyncPlan(
    val planId: String,
    val endpointId: String,
    val endpointName: String,
    val scope: String,
    val selectionJson: String,
    val deliveryChoiceJson: String,
    val itemsJson: String,
    val manifestVersion: Int,
    val manifestChecksum: String,
    val totalSelectedTracks: Int,
    val tracksToDownloadCount: Int,
    val totalDownloadBytes: Long,
    val status: String,
    val executionState: String = "QUEUED",
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
)

interface ApprovedSyncPlanRepository {
    suspend fun savePlan(plan: SyncPlan, manifestVersion: Int = 1, manifestChecksum: String = ""): ApprovedSyncPlan
    suspend fun getPlan(planId: String): ApprovedSyncPlan?
    suspend fun getLatestPlanForEndpoint(endpointId: String): ApprovedSyncPlan?
    suspend fun updateExecutionState(
        planId: String,
        state: TransferLifecycleState,
        errorMessage: String? = null,
    )
    suspend fun deletePlan(planId: String)
}
