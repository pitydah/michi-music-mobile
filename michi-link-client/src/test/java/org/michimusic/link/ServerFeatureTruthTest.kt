package org.michimusic.link

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.link.dto.ServerInfoDto

/** MIM-03: missing, explicit-empty and explicit-false feature maps are distinct. */
class ServerFeatureTruthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `absent features returns null and preserves history`() {
        val info = json.decodeFromString<ServerInfoDto>("""{"service":"michi-micro-server"}""")
        assertNull(info.verifiedFeatures)
        assertEquals(listOf("aircat"), mergeServerFeatures(listOf("aircat"), info))
    }

    @Test
    fun `explicit empty features clears history`() {
        val info = json.decodeFromString<ServerInfoDto>("""{"service":"michi-micro-server","features":{}}""")
        assertEquals(emptyList<String>(), info.verifiedFeatures)
        assertEquals(emptyList<String>(), mergeServerFeatures(listOf("aircat"), info))
    }

    @Test
    fun `explicit false feature is not treated as enabled`() {
        val info = json.decodeFromString<ServerInfoDto>(
            """{"service":"michi-micro-server","features":{"playback":false,"aircat_original_v1":true}}""",
        )
        assertEquals(listOf("aircat_original_v1"), info.verifiedFeatures)
        assertEquals(listOf("aircat_original_v1"), mergeServerFeatures(listOf("legacy"), info))
    }
}
