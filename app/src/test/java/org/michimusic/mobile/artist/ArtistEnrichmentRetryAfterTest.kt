package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Retry-After contract for provider throttling. MusicBrainz and Wikimedia send seconds;
 * the value must be bounded so a hostile or broken header cannot freeze the artist screen.
 */
class ArtistEnrichmentRetryAfterTest {

    @Test
    fun secondsAreConvertedToAMeasurableDelay() {
        assertEquals(2_000L, retryAfterDelayMs("2"))
        assertEquals(3_000L, retryAfterDelayMs(" 3 "))
    }

    @Test
    fun missingOrNonNumericHeadersFallBackToBackoff() {
        assertEquals(0L, retryAfterDelayMs(null))
        assertEquals(0L, retryAfterDelayMs(""))
        assertEquals(0L, retryAfterDelayMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(0L, retryAfterDelayMs("soon"))
    }

    @Test
    fun zeroAndNegativeValuesNeverWait() {
        assertEquals(0L, retryAfterDelayMs("0"))
        assertEquals(0L, retryAfterDelayMs("-30"))
    }

    @Test
    fun anExtremeHeaderIsCapped() {
        assertEquals(MAX_RETRY_AFTER_MS, retryAfterDelayMs("999999"))
    }
}
