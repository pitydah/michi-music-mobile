package org.michimusic.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.core.models.ApprovedSyncPlan
import org.michimusic.core.models.ApprovedSyncPlanRepository
import org.michimusic.core.models.SyncPlan
import org.michimusic.core.models.TransferLifecycleState
import org.michimusic.data.cache.ApprovedSyncPlanDao
import org.michimusic.data.cache.ApprovedSyncPlanEntity

class RoomApprovedSyncPlanRepository(
    private val dao: ApprovedSyncPlanDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ApprovedSyncPlanRepository {

    override suspend fun savePlan(
        plan: SyncPlan,
        manifestVersion: Int,
        manifestChecksum: String,
    ): ApprovedSyncPlan {
        val entity = ApprovedSyncPlanEntity(
            planId = plan.planId,
            endpointId = plan.endpointId,
            endpointName = plan.endpointName,
            scope = plan.selection.scope.name,
            selectionJson = json.encodeToString(plan.selection),
            deliveryChoiceJson = json.encodeToString(plan.deliveryChoice),
            itemsJson = json.encodeToString(plan.items),
            manifestVersion = manifestVersion,
            manifestChecksum = manifestChecksum,
            totalSelectedTracks = plan.totalSelectedTracks,
            tracksToDownloadCount = plan.tracksToDownloadCount,
            totalDownloadBytes = plan.totalDownloadBytes,
            status = plan.status.name,
            executionState = TransferLifecycleState.QUEUED.name,
            createdAt = plan.timestampMs,
        )
        dao.insertPlan(entity)
        return entity.toDomain()
    }

    override suspend fun getPlan(planId: String): ApprovedSyncPlan? {
        return dao.getPlan(planId)?.toDomain()
    }

    override suspend fun getLatestPlanForEndpoint(endpointId: String): ApprovedSyncPlan? {
        return dao.getLatestPlanForEndpoint(endpointId)?.toDomain()
    }

    override suspend fun updateExecutionState(
        planId: String,
        state: TransferLifecycleState,
        errorMessage: String?,
    ) {
        val currentPlan = dao.getPlan(planId) ?: return
        val currentState = runCatching { TransferLifecycleState.valueOf(currentPlan.executionState) }.getOrNull()

        // Enforce valid transitions:
        // Terminal states cannot be transitioned out of
        if (currentState in setOf(TransferLifecycleState.COMPLETED, TransferLifecycleState.PARTIAL_SUCCESS, TransferLifecycleState.CANCELLED)) {
            // Cannot transition from terminal state unless re-queued
            if (state != TransferLifecycleState.QUEUED && state != TransferLifecycleState.RETRY_PENDING) {
                return
            }
        }

        val now = System.currentTimeMillis()
        val executedAt = if (state == TransferLifecycleState.PREPARING || state == TransferLifecycleState.TRANSFERRING) now else null
        val completedAt = if (state in setOf(
                TransferLifecycleState.COMPLETED,
                TransferLifecycleState.PARTIAL_SUCCESS,
                TransferLifecycleState.FAILED,
                TransferLifecycleState.CANCELLED
            )) now else null

        dao.updateExecutionState(
            planId = planId,
            state = state.name,
            executedAt = executedAt,
            completedAt = completedAt,
            errorMessage = errorMessage,
        )
    }

    override suspend fun deletePlan(planId: String) {
        dao.deletePlan(planId)
    }

    private fun ApprovedSyncPlanEntity.toDomain(): ApprovedSyncPlan {
        return ApprovedSyncPlan(
            planId = planId,
            endpointId = endpointId,
            endpointName = endpointName,
            scope = scope,
            selectionJson = selectionJson,
            deliveryChoiceJson = deliveryChoiceJson,
            itemsJson = itemsJson,
            manifestVersion = manifestVersion,
            manifestChecksum = manifestChecksum,
            totalSelectedTracks = totalSelectedTracks,
            tracksToDownloadCount = tracksToDownloadCount,
            totalDownloadBytes = totalDownloadBytes,
            status = status,
            executionState = executionState,
            createdAt = createdAt,
            executedAt = executedAt,
            completedAt = completedAt,
            errorMessage = errorMessage,
        )
    }
}
