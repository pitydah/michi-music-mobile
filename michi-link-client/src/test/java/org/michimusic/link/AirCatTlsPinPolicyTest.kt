package org.michimusic.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class AirCatTlsPinPolicyTest {
    private val pin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test fun authenticatedTransportRequiresHttpsAndExactPin() {
        assertNull(AirCatTlsPinPolicy.refusal("https://music.local:8500", pin))
        assertEquals("AIRC_TLS_REQUIRED", AirCatTlsPinPolicy.refusal("http://192.168.1.20:8500", pin))
        assertEquals("AIRC_TLS_PIN_REQUIRED", AirCatTlsPinPolicy.refusal("https://music.local", ""))
        assertEquals("AIRC_TLS_PIN_REQUIRED", AirCatTlsPinPolicy.refusal("https://music.local", "sha256/0"))
        assertEquals("AIRC_TLS_REQUIRED", AirCatTlsPinPolicy.refusal("https://user:pass@music.local", pin))
        assertEquals("AIRC_TLS_REQUIRED", AirCatTlsPinPolicy.refusal("https://music.local/?token=abc", pin))
    }
}
