package org.michimusic.link

import org.junit.Assert.fail
import org.junit.Test

class LinkClientConfigR16Test {
    @Test fun permitsLocalHttpAndHttps() {
        LinkClientConfig.normalizeBaseUrl("http://192.168.1.12:53318")
        LinkClientConfig.normalizeBaseUrl("https://michi.example.com")
    }

    @Test fun rejectsUnsafeSchemesAndUrlCredentials() {
        for (url in listOf("javascript:alert(1)", "ftp://127.0.0.1", "http://public.example.com",
            "https://user:pass@michi.example.com", "http://127.0.0.1:0", "", "http://")) {
            try { LinkClientConfig.normalizeBaseUrl(url); fail("Accepted $url") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun rejectsPublicIPv6AndFalsePrivatePrefixes() {
        for (unsafe in listOf("http://fdpublic.example.com", "http://[2001:db8::1]:53318",
            "http://arbitraryhost:53318", "http://public:80")) {
            try { LinkClientConfig.normalizeBaseUrl(unsafe); fail("Accepted $unsafe") }
            catch (_: IllegalArgumentException) { }
        }
        LinkClientConfig.normalizeBaseUrl("http://[fd00::1]:53318")
        LinkClientConfig.normalizeBaseUrl("http://[fe80::1]:53318")
    }
}
