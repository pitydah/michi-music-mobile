package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonsImageInfoTest {
    private fun client(requests: MutableList<String>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            requests += url.toString()
            val body = when {
                url.queryParameter("prop") == "info" ->
                    """{"query":{"pages":{"123":{"pageid":123,"title":"File:Midnight Oil.jpg"}}}}"""
                url.queryParameter("ids") == "M123" ->
                    """{"entities":{"M123":{"statements":{"P180":[{"mainsnak":{"datavalue":{"value":{"id":"Q48284"}}}}]}}}}"""
                else -> """{"error":"unexpected"}"""
            }.toResponseBody("application/json".toMediaType())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "application/json")
                .body(body).build()
        }).build()

    @Test
    fun depictsUsesCommonsMediaInfoEntityNotTheArtistEntity() = runBlocking {
        val requests = mutableListOf<String>()
        val commons = CommonsImageInfo(ArtworkHttp(client(requests)))

        assertTrue(commons.depicts("Midnight Oil.jpg", "Q48284"))
        assertTrue(requests.all { it.contains("commons.wikimedia.org") })
        assertTrue(requests.any { it.contains("pageid") || it.contains("prop=info") })
        assertTrue(requests.any { it.contains("ids=M123") })
        assertFalse(requests.any { it.contains("www.wikidata.org") })
    }

    @Test
    fun depictsRejectsAFileWhoseMediaInfoP180DoesNotMatchTheQid() = runBlocking {
        val requests = mutableListOf<String>()
        val commons = CommonsImageInfo(ArtworkHttp(client(requests)))

        assertFalse(commons.depicts("Midnight Oil.jpg", "Q300409"))
    }
}
