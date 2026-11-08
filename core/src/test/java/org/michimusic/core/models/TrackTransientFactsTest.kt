package org.michimusic.core.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackTransientFactsTest {
    @Test
    fun localSourceFactsAreNotSerialized() {
        val json = Json.encodeToString(
            Track(
                id = "local-1",
                title = "Song",
                sourceMimeType = "audio/mpeg",
                sourceFileExtension = "mp3",
                trackNumberFromMediaStore = true,
            ),
        )

        assertFalse(json.contains("sourceMimeType"))
        assertFalse(json.contains("sourceFileExtension"))
        assertFalse(json.contains("trackNumberFromMediaStore"))
    }
}
