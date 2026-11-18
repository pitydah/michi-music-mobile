package org.michimusic.link

import java.io.IOException
import java.io.OutputStream

/**
 * A38/A40: manifest-size budget and event-driven write-continuity boundary.
 * A managed AirCat transfer requires a pre-write guard; no background worker,
 * timer, network client or extra copy buffer is created.
 */
internal class AirCatBoundedOutputStream(
    private val delegate: OutputStream,
    private val maximumAdditionalBytes: Long,
    private val beforeWrite: ((Int) -> Unit)? = null,
) : OutputStream() {
    init { require(maximumAdditionalBytes >= 0L) }

    private companion object {
        // A large Ktor read must not bypass the revocation/cancel boundary.
        const val MAX_GUARDED_SLICE = 64 * 1024
    }

    var acceptedBytes: Long = 0L
        private set

    private fun ensureRoom(length: Long) {
        if (length < 0L || length > maximumAdditionalBytes - acceptedBytes)
            throw IOException("AIRC_ORIGINAL_SIZE_BUDGET_EXCEEDED")
    }

    override fun write(value: Int) {
        ensureRoom(1L)
        beforeWrite?.invoke(1)
        delegate.write(value)
        acceptedBytes += 1L
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length)
            throw IndexOutOfBoundsException("Invalid output buffer range")
        // A38: reject an oversized caller chunk in full, before ANY write.
        ensureRoom(length.toLong())
        if (length == 0) return
        if (beforeWrite == null) {
            delegate.write(bytes, offset, length)
            acceptedBytes += length.toLong()
            return
        }
        // A40: guard *before* each bounded slice, not after delivery to .part.
        // A revoked Home cannot stream arbitrarily large HTTP read buffers
        // in one unchecked OutputStream.write(...).
        var cursor = offset
        var left = length
        while (left > 0) {
            val slice = minOf(left, MAX_GUARDED_SLICE)
            beforeWrite.invoke(slice)
            delegate.write(bytes, cursor, slice)
            acceptedBytes += slice.toLong()
            cursor += slice
            left -= slice
        }
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
