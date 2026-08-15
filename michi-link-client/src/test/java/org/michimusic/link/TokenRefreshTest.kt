package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.TokenRefreshResponseDto

class TokenRefreshTest {

    @Test
    fun `effectiveToken resolves accessToken when present`() {
        val dto = TokenRefreshResponseDto(
            accessToken = "access_tok_123",
            deviceToken = "dev_tok_456",
            expiresIn = 3600L
        )
        assertEquals("access_tok_123", dto.effectiveToken)
    }

    @Test
    fun `effectiveToken falls back to deviceToken when accessToken is empty`() {
        val dto = TokenRefreshResponseDto(
            accessToken = "",
            deviceToken = "dev_tok_456",
            expiresIn = 3600L
        )
        assertEquals("dev_tok_456", dto.effectiveToken)
    }

    @Test
    fun `refreshToken handles OpenAPI v1 response format`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """{"access_token":"new_jwt_token_999","expires_in":7200,"token_type":"Bearer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:7331",
            deviceToken = "old_token",
            clientDeviceId = "mobile_client_1",
            httpClient = mockHttpClient,
        )

        val result = client.refreshToken("refresh_tok_abc", force = true)
        assertTrue(result.isSuccess)
        val resp = result.getOrThrow()
        assertEquals("new_jwt_token_999", resp.effectiveToken)
        assertEquals("new_jwt_token_999", client.deviceToken)
    }
}
