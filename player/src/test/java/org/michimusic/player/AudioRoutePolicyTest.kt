package org.michimusic.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutePolicyTest {
    private val dacA = OutputDescriptor(101, "DAC A", 11)
    private val dacB = OutputDescriptor(102, "DAC B", 11)
    private val speaker = OutputDescriptor(9, "Altavoz", 2)

    @Test fun matchingRouteIsConfirmed() {
        val route = classifyObservedAudioRoute(dacA, dacA, 10)
        assertTrue(route is AudioRouteStatus.Confirmed)
        assertEquals(dacA, currentlyObservedOutput(route))
    }

    @Test fun requestedUsbButRoutedToSpeakerIsNotConfirmedMatch() {
        val route = classifyObservedAudioRoute(dacB, speaker, 20)
        assertTrue(route is AudioRouteStatus.RoutedElsewhere)
        assertEquals(speaker, currentlyObservedOutput(route))
    }

    @Test fun historicalRequestedAndUnknownRoutesAreNotLiveEvidence() {
        assertNull(currentlyObservedOutput(AudioRouteStatus.LastObserved(dacA, 10)))
        assertNull(currentlyObservedOutput(AudioRouteStatus.Requested(dacB)))
        assertNull(currentlyObservedOutput(AudioRouteStatus.Unknown("route unavailable")))
        assertNull(currentlyObservedOutput(AudioRouteStatus.Failed(102, RouteFailure.DISCONNECTED)))
    }

    @Test fun unpluggingSelectedBIsDetectedWhileAStaysPresent() {
        assertEquals(102, missingSelectedUsbOutput(102, 101, setOf(101)))
        assertEquals(101, missingSelectedUsbOutput(null, 101, setOf(102)))
        assertNull(missingSelectedUsbOutput(102, 101, setOf(101, 102)))
    }

    @Test fun usbRoutingMismatchRequiresFailClosedPause() {
        val mismatch = classifyObservedAudioRoute(dacB, speaker, 57)
        assertTrue(usbRouteMismatchRequiresPause(mismatch, setOf(11)))
        assertTrue(!usbRouteMismatchRequiresPause(mismatch, setOf(99)))
        assertTrue(!usbRouteMismatchRequiresPause(classifyObservedAudioRoute(dacB, dacB, 59), setOf(11)))
        assertTrue(!usbRouteMismatchRequiresPause(AudioRouteStatus.Requested(dacB), setOf(11)))
    }

    @Test fun repeatedIdenticalUsbAdvertisementIsNotNewSelection() {
        assertTrue(sameRequestedRoute(dacA, dacA.copy()))
        assertTrue(sameRequestedRoute(null, null))
        assertTrue(!sameRequestedRoute(dacA, dacB))
        assertTrue(!sameRequestedRoute(dacA, dacA.copy(name = "changed identity")))
    }

    @Test fun defaultRouteIsOnlyConfirmedWhenObserved() {
        assertTrue(classifyObservedAudioRoute(null, speaker, 30) is AudioRouteStatus.Confirmed)
        assertNull(currentlyObservedOutput(AudioRouteStatus.NoDevice))
    }
}
