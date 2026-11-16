package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirCatByteTotalPolicyTest {
    @Test fun emptyIsZero() = assertEquals(0L, AirCatByteTotalPolicy.exactTotal(emptyList()))
    @Test fun oneValid() = assertEquals(1L, AirCatByteTotalPolicy.exactTotal(listOf(1L)))
    @Test fun aggregatesMany() = assertEquals(10L, AirCatByteTotalPolicy.exactTotal(listOf(1L, 2L, 3L, 4L)))
    @Test fun zeroSizeRejected() = assertNull(AirCatByteTotalPolicy.exactTotal(listOf(1L, 0L)))
    @Test fun negativeSizeRejected() = assertNull(AirCatByteTotalPolicy.exactTotal(listOf(-2L)))
    @Test fun overflowRejected() = assertNull(AirCatByteTotalPolicy.exactTotal(listOf(Long.MAX_VALUE, 1L)))
    @Test fun maxSingleAllowed() = assertEquals(Long.MAX_VALUE, AirCatByteTotalPolicy.exactTotal(listOf(Long.MAX_VALUE)))
    @Test fun tooManyEntriesRejected() = assertNull(AirCatByteTotalPolicy.exactTotal(List(10001) { 1L }))
    @Test fun maximumBatchAccepted() = assertEquals(10000L, AirCatByteTotalPolicy.exactTotal(List(10000) { 1L }))
}
