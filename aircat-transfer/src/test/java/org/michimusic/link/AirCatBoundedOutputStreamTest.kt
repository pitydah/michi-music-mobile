package org.michimusic.link

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirCatBoundedOutputStreamTest {
    private val source = byteArrayOf(10, 20, 30, 40, 50)

    @Test fun exactSizeAccepted() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 5)
        guarded.write(source)
        assertEquals(5L, guarded.acceptedBytes)
        assertArrayEquals(source, out.toByteArray())
    }

    @Test fun oversizeChunkRejectedBeforeAnyWrite() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 4)
        var refused = false
        try { guarded.write(source) } catch (_: IOException) { refused = true }
        assertTrue(refused)
        assertEquals(0L, guarded.acceptedBytes)
        assertEquals(0, out.size())
    }

    @Test fun noPartialChunkAfterBudget() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 3)
        guarded.write(source, 0, 2)
        var refused = false
        try { guarded.write(source, 2, 2) } catch (_: IOException) { refused = true }
        assertTrue(refused)
        assertEquals(2L, guarded.acceptedBytes)
        assertArrayEquals(byteArrayOf(10, 20), out.toByteArray())
    }

    @Test fun zeroBudgetRejectsSingleByte() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 0)
        var refused = false
        try { guarded.write(20) } catch (_: IOException) { refused = true }
        assertTrue(refused)
        assertEquals(0, out.size())
    }

    @Test fun negativeBudgetRejected() {
        var refused = false
        try { AirCatBoundedOutputStream(ByteArrayOutputStream(), -1) } catch (_: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }

    @Test fun invalidSourceOffsetRejectedWithoutWrite() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 10)
        var refused = false
        try { guarded.write(source, 4, 2) } catch (_: IndexOutOfBoundsException) { refused = true }
        assertTrue(refused)
        assertEquals(0, out.size())
    }

    @Test fun resumedTransferOnlyGetsRemainingBudget() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 8L - 5L)
        guarded.write(source, 0, 3)
        var refused = false
        try { guarded.write(99) } catch (_: IOException) { refused = true }
        assertTrue(refused)
        assertEquals(3, out.size())
    }

    @Test fun exactLimitCanBeReachedBytewise() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 2)
        guarded.write(10)
        guarded.write(20)
        assertArrayEquals(byteArrayOf(10,20), out.toByteArray())
        assertEquals(2L, guarded.acceptedBytes)
    }

    @Test fun zeroLengthWriteAllowedAtLimit() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 0)
        guarded.write(source, 0, 0)
        assertEquals(0L, guarded.acceptedBytes)
    }

    @Test fun hugeLimitAvoidsArithmeticOverflow() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, Long.MAX_VALUE)
        guarded.write(source)
        assertEquals(5L, guarded.acceptedBytes)
    }

    @Test fun closePropagatesToDelegate() {
        var closed = false
        val delegate = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        AirCatBoundedOutputStream(delegate, 10).close()
        assertTrue(closed)
    }

    @Test fun managerMustNotReportAnOverrunAsSuccess() {
        val out = ByteArrayOutputStream()
        val guarded = AirCatBoundedOutputStream(out, 2)
        guarded.write(byteArrayOf(1,2))
        var succeeded = true
        try { guarded.write(byteArrayOf(3)) } catch (_: IOException) { succeeded = false }
        assertFalse(succeeded)
        assertArrayEquals(byteArrayOf(1,2), out.toByteArray())
    }
}
