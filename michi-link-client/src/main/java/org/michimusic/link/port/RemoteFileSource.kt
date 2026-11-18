package org.michimusic.link.port

import org.michimusic.link.StreamRangeResult
import java.io.OutputStream

/** Byte-range streaming only. This port does not grant trust or authorization.
 * LinkClient remains the sole owner of authentication, HTTP and connection pooling.
 */
interface RemoteFileSource {
    // A24: only a raw file source may be used by managed AirCat. This is a
    // representation contract, NOT cryptographic provenance or server trust.
    val servesOriginalBytes: Boolean get() = false

    // Explicit transport attestation. `servesOriginalBytes` alone is not security.
    val verifiedTransport: Boolean get() = false

    suspend fun streamTrackRange(
        trackId: String,
        outputStream: OutputStream,
        startBytes: Long = 0L,
        expectedEtag: String? = null,
        bufferSize: Int = 65536,
        onProgress: ((bytesRead: Long, totalExpected: Long?) -> Unit)? = null,
    ): Result<StreamRangeResult>
}

/** A27: a network resource, not a raw-source marker; caller owns lifecycle. */
interface PinnedOriginalFileSource : RemoteFileSource, java.io.Closeable
