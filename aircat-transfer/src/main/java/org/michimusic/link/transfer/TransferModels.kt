package org.michimusic.link.transfer

enum class TransferState {
    QUEUED,
    PREFLIGHT,
    DOWNLOADING,
    VERIFYING,
    COMMITTING,
    COMPLETED,
    PAUSED,
    FAILED,
}

enum class ResumeMode {
    RESUMED_FROM_OFFSET,
    RESTARTED_FROM_ZERO,
    NOT_APPLICABLE,
}

data class TransferCheckpoint(
    val trackId: String,
    val tempPath: String,
    val finalPath: String,
    val bytesVerified: Long = 0L,
    val expectedSize: Long = 0L,
    val etag: String = "",
    val sha256Expected: String = "",
    val resumeMode: ResumeMode = ResumeMode.NOT_APPLICABLE,
    val state: TransferState = TransferState.QUEUED,
    val updatedAt: Long = System.currentTimeMillis(),
)

sealed class TransferProgress {
    data object Queued : TransferProgress()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val resumeMode: ResumeMode = ResumeMode.NOT_APPLICABLE,
    ) : TransferProgress()
    data class Verifying(val bytesVerified: Long, val totalBytes: Long) : TransferProgress()
    data class Completed(val totalBytes: Long, val finalPath: String) : TransferProgress()
    data class Paused(val bytesPreserved: Long, val totalBytes: Long) : TransferProgress()
    data class Failed(val error: String, val isRetryable: Boolean = true) : TransferProgress()
}

class TransferChecksumMismatchException(val trackId: String, val expected: String, val actual: String) :
    Exception("Checksum SHA-256 falló para track $trackId: esperado=$expected, actual=$actual")

class TransferSizeMismatchException(val trackId: String, val expected: Long, val actual: Long) :
    Exception("Tamaño de descarga no coincide para $trackId: esperado=$expected, recibido=$actual")

class TransferPromotionException(val trackId: String, message: String) :
    Exception("No se pudo promover archivo descargado para $trackId: $message")

class TransferETagMismatchException(val trackId: String, val expected: String, val actual: String) :
    Exception("ETag del servidor cambió durante reanudación de $trackId: $expected vs $actual")
