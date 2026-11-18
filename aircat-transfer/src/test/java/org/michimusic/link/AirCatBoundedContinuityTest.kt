package org.michimusic.link

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AirCatBoundedContinuityTest {
    @Test fun splitsLargeWriteAtGuardBoundariesWithoutExtraBuffer() {
        val data = ByteArray(180_000) { (it and 0xff).toByte() }
        val target = ByteArrayOutputStream()
        val visits = mutableListOf<Int>()
        AirCatBoundedOutputStream(target, data.size.toLong()) { visits.add(it) }.use {
            it.write(data)
        }
        assertEquals(listOf(65536, 65536, 48928), visits)
        assertArrayEquals(data, target.toByteArray())
    }

    @Test fun midWriteRefusalPreservesPartialAsUncommittedPart() {
        val target = ByteArrayOutputStream()
        var checks = 0
        val output = AirCatBoundedOutputStream(target, 200_000L) {
            if (++checks == 2) throw IOException("AIRC_TEST_REVOKED")
        }
        assertThrows(IOException::class.java) { output.write(ByteArray(180_000)) }
        assertEquals(65_536, target.size())
        assertEquals(65_536L, output.acceptedBytes)
        assertEquals(2, checks)
    }

    @Test fun oversizeRejectsWholeInputBeforeWritingAndGuard() {
        val target = ByteArrayOutputStream()
        var checks = 0
        val output = AirCatBoundedOutputStream(target, 10L) { checks++ }
        assertThrows(IOException::class.java) { output.write(ByteArray(11)) }
        assertEquals(0, checks)
        assertEquals(0, target.size())
    }

    @Test fun singleByteConsultsGuardBeforeMutation() {
        val target = ByteArrayOutputStream()
        val output = AirCatBoundedOutputStream(target, 2L) { throw IOException("STOP") }
        assertThrows(IOException::class.java) { output.write(7) }
        assertEquals(0, target.size())
    }
}
