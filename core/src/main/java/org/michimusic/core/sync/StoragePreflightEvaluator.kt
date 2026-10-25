package org.michimusic.core.sync

import org.michimusic.core.models.IssueSeverity
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncItemDecision
import org.michimusic.core.models.SyncPreflightIssue
import org.michimusic.core.models.SyncPreflightStatus

object StoragePreflightEvaluator {

    /**
     * Strictly enforced 100 MB safety reserve. The sync preflight MUST NEVER allow
     * filling the device beyond this safety boundary.
     */
    const val SAFETY_BUFFER_BYTES: Long = 100L * 1024L * 1024L // 100 MB

    /**
     * Buffer reserved for temp file atomic rename and directory metadata overhead.
     * Calculated as max(10 MB, 5% of total download size).
     */
    const val MIN_ATOMIC_BUFFER_BYTES: Long = 10L * 1024L * 1024L // 10 MB

    data class StorageEvaluationResult(
        val totalDownloadBytes: Long,
        val safetyBufferBytes: Long,
        val atomicBufferBytes: Long,
        val remainingAfterSyncBytes: Long,
        val status: SyncPreflightStatus,
        val issues: List<SyncPreflightIssue>,
    )

    private fun safeAdd(a: Long, b: Long): Long {
        return try {
            Math.addExact(a, b)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    fun calculateAtomicBuffer(totalDownloadBytes: Long): Long {
        if (totalDownloadBytes <= 0L) return 0L
        if (totalDownloadBytes > Long.MAX_VALUE / 20) {
            return Long.MAX_VALUE
        }
        val proportional = (totalDownloadBytes * 0.05).toLong()
        return maxOf(MIN_ATOMIC_BUFFER_BYTES, proportional)
    }

    fun evaluate(
        availableBytes: Long,
        items: List<SyncItemDecision>,
    ): StorageEvaluationResult {
        var totalDownloadBytes = 0L
        var hasInvalidMetadata = false
        for (item in items) {
            if (item.sourceSizeBytes < 0L || item.estimatedDownloadBytes < 0L) {
                hasInvalidMetadata = true
            }
            val bytes = when (item.action) {
                SyncItemAction.DOWNLOAD -> item.estimatedDownloadBytes
                SyncItemAction.RESUME_PARTIAL -> item.estimatedDownloadBytes
                SyncItemAction.REPLACE_BETTER_QUALITY -> item.estimatedDownloadBytes
                SyncItemAction.SKIP_ALREADY_EXISTS -> 0L
                SyncItemAction.BLOCKED_UNSUPPORTED -> 0L
            }
            if (bytes > 0L) {
                totalDownloadBytes = safeAdd(totalDownloadBytes, bytes)
            }
        }

        val issues = mutableListOf<SyncPreflightIssue>()
        val status: SyncPreflightStatus

        if (hasInvalidMetadata) {
            status = SyncPreflightStatus.BLOCKED_INVALID_SELECTION
            issues.add(
                SyncPreflightIssue(
                    code = "METADATA_INVALID",
                    message = "Metadatos de pista corruptos o tamaño inválido detectado.",
                    severity = IssueSeverity.BLOCKING,
                )
            )
            return StorageEvaluationResult(
                totalDownloadBytes = totalDownloadBytes,
                safetyBufferBytes = SAFETY_BUFFER_BYTES,
                atomicBufferBytes = 0L,
                remainingAfterSyncBytes = if (availableBytes > 0L) availableBytes else 0L,
                status = status,
                issues = issues,
            )
        }

        if (availableBytes <= 0L) {
            status = SyncPreflightStatus.BLOCKED_NO_STORAGE
            issues.add(
                SyncPreflightIssue(
                    code = "STORAGE_INVALID",
                    message = "Almacenamiento no accesible o espacio disponible inválido.",
                    severity = IssueSeverity.BLOCKING,
                )
            )
            return StorageEvaluationResult(
                totalDownloadBytes = totalDownloadBytes,
                safetyBufferBytes = SAFETY_BUFFER_BYTES,
                atomicBufferBytes = 0L,
                remainingAfterSyncBytes = 0L,
                status = status,
                issues = issues,
            )
        }

        val atomicBuffer = if (totalDownloadBytes > 0) calculateAtomicBuffer(totalDownloadBytes) else 0L
        val totalRequiredPeak = safeAdd(safeAdd(totalDownloadBytes, SAFETY_BUFFER_BYTES), atomicBuffer)
        val remainingAfterPeak = if (availableBytes >= totalRequiredPeak) availableBytes - totalRequiredPeak else 0L
        val remainingAfterSync = if (availableBytes >= totalDownloadBytes) availableBytes - totalDownloadBytes else 0L

        if (totalDownloadBytes > 0 && (totalRequiredPeak == Long.MAX_VALUE || availableBytes < totalRequiredPeak)) {
            status = SyncPreflightStatus.BLOCKED_NO_STORAGE
            issues.add(
                SyncPreflightIssue(
                    code = "STORAGE_EXCEEDED",
                    message = "Espacio insuficiente: se necesitan ${formatBytes(totalDownloadBytes)} de descarga + ${formatBytes(SAFETY_BUFFER_BYTES)} de reserva de seguridad y ${formatBytes(atomicBuffer)} de búfer de promoción atómica, pero solo hay ${formatBytes(availableBytes)} libres.",
                    severity = IssueSeverity.BLOCKING,
                )
            )
        } else {
            status = SyncPreflightStatus.READY
            if (totalDownloadBytes > 0 && remainingAfterSync < SAFETY_BUFFER_BYTES * 2) {
                issues.add(
                    SyncPreflightIssue(
                        code = "LOW_STORAGE_WARNING",
                        message = "El almacenamiento libre restante quedará por debajo de ${formatBytes(SAFETY_BUFFER_BYTES * 2)} tras la sincronización.",
                        severity = IssueSeverity.WARNING,
                    )
                )
            }
        }

        return StorageEvaluationResult(
            totalDownloadBytes = totalDownloadBytes,
            safetyBufferBytes = SAFETY_BUFFER_BYTES,
            atomicBufferBytes = atomicBuffer,
            remainingAfterSyncBytes = remainingAfterSync,
            status = status,
            issues = issues,
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.2f GB", gb)
    }
}
