package org.michimusic.link

/** HTTP 200 alone does not establish an SSE connection. Fail closed on a missing MIME type. */
internal object SseHandshakePolicy {
    fun accepts(statusCode: Int, contentType: String?): Boolean =
        statusCode == 200 && contentType?.substringBefore(';')?.trim()
            ?.equals("text/event-stream", ignoreCase = true) == true
}
