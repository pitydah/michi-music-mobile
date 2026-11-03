package org.michimusic.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SseHandshakePolicyTest {
    @Test fun acceptsValidSseWithOptionalCharset() {
        assertTrue(SseHandshakePolicy.accepts(200, "text/event-stream"))
        assertTrue(SseHandshakePolicy.accepts(200, "text/event-stream; charset=UTF-8"))
        assertTrue(SseHandshakePolicy.accepts(200, "Text/Event-Stream ; charset=utf-8"))
    }

    @Test fun rejectsInvalidStatusMissingOrMisleadingContentType() {
        for (status in listOf(201, 301, 401, 403, 404, 500)) {
            assertFalse(SseHandshakePolicy.accepts(status, "text/event-stream"))
        }
        for (type in listOf(null, "", "application/json", "text/event-streamer", "text/html")) {
            assertFalse(SseHandshakePolicy.accepts(200, type))
        }
    }
}
