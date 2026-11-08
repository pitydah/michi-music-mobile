package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroSharedNowPlayingMotionTest {
    @Test fun playerArtworkAlwaysUsesTheLocalFallback() {
        assertFalse(aeroNowPlayingTransitionPolicy().shareArtwork)
        assertFalse(aeroNowPlayingTransitionPolicy(reducedMotion = true).shareArtwork)
        assertFalse(aeroNowPlayingTransitionPolicy(highContrast = true).shareArtwork)
    }

    @Test fun ordinaryPresentationKeepsFiniteLocalMotion() {
        val policy = aeroNowPlayingTransitionPolicy()
        assertTrue(policy.animateMiniPlayerVisibility)
        assertTrue(policy.animateNowPlayingHero)
    }

    @Test fun reducedMotionUsesAnImmediateLocalTransition() {
        val policy = aeroNowPlayingTransitionPolicy(reducedMotion = true)
        assertFalse(policy.animateMiniPlayerVisibility)
        assertFalse(policy.animateNowPlayingHero)
    }

    @Test fun highContrastUsesAnImmediateLocalTransition() {
        val policy = aeroNowPlayingTransitionPolicy(highContrast = true)
        assertFalse(policy.animateMiniPlayerVisibility)
        assertFalse(policy.animateNowPlayingHero)
    }
}
