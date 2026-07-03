package org.michimusic.link

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkClientConfigTest {
    @Test
    fun normalizeBaseUrl_trimsWhitespaceAndTrailingSlashes() {
        assertEquals(
            "http://192.168.1.10:53318",
            LinkClientConfig.normalizeBaseUrl("  http://192.168.1.10:53318///  "),
        )
    }
}
