package org.michimusic.mobile.ui.components

import org.junit.Assert.*
import org.junit.Test

class ManualEndpointTest {
    @Test fun `accepts IPv4 with explicit port`() {
        assertEquals("192.168.1.20:7331", parseManualEndpoint(" 192.168.1.20:7331 ")?.authority)
    }
    @Test fun `uses documented default port`() {
        assertEquals("michi.local:8400", parseManualEndpoint("Michi.local")?.authority)
    }
    @Test fun `rejects invalid explicit ports rather than connecting elsewhere`() {
        listOf("server:", "server:0", "server:65536", "server:abc", "server:-1", "server:12:44").forEach {
            assertNull(parseManualEndpoint(it))
        }
    }
    @Test fun `rejects malformed IPv4 and URL syntax`() {
        listOf("256.1.1.1", "1.2.3", "http://host:8400", "host/path", "abc def", "-host.local").forEach {
            assertNull(parseManualEndpoint(it))
        }
    }
    @Test fun `does not silently corrupt IPv6 addresses`() {
        assertNull(parseManualEndpoint("[fe80::1]:8400"))
        assertNull(parseManualEndpoint("fe80::1"))
    }
}
