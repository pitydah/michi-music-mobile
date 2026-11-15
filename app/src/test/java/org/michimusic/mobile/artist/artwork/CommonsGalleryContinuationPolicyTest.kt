package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommonsGalleryContinuationPolicyTest {
    @Test fun validMediaWikiContinuationIsScopedToOneAdditionalPage() {
        val next = CommonsGalleryContinuationPolicy.next("File:next|12", "-||", 0, null)
        assertEquals(CommonsGalleryContinuationPolicy.Tokens("File:next|12", "-||"), next)
        assertNull(CommonsGalleryContinuationPolicy.next("File:third", "-||", 1, next))
    }

    @Test fun missingMalformedOrRepeatedTokensFailClosed() {
        val prior = CommonsGalleryContinuationPolicy.Tokens("File:next|12", "-||")
        assertNull(CommonsGalleryContinuationPolicy.next("File:next|12", "-||", 0, prior))
        assertNull(CommonsGalleryContinuationPolicy.next(null, "-||", 0, null))
        assertNull(CommonsGalleryContinuationPolicy.next("File:next", null, 0, null))
        assertNull(CommonsGalleryContinuationPolicy.next("File:next\n", "-||", 0, null))
        assertNull(CommonsGalleryContinuationPolicy.next("File:next", "-||\u0000", 0, null))
        assertNull(CommonsGalleryContinuationPolicy.next("x".repeat(513), "-||", 0, null))
        assertNull(CommonsGalleryContinuationPolicy.next("File:next", "-||", -1, null))
    }
}
