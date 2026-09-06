package com.opengpt.codex

import com.opengpt.auth.TokenRefreshService
import com.opengpt.config.CodexProperties
import com.opengpt.model.OAuthToken
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

class CodexClientWireMockTest {
    private lateinit var server: WireMockServer

    @BeforeEach
    fun start() {
        server = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop()
    }

    @Test
    fun `sends oauth headers to codex endpoint`() {
        server.stubFor(
            post(urlEqualTo("/backend-api/codex/responses"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"type\":\"response.completed\"}\n\n"),
                ),
        )

        val token =
            OAuthToken(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresAt = System.currentTimeMillis() + 60_000,
                accountId = "acc-1",
                residency = "eu",
            )

        val refreshService =
            object : TokenRefreshService(
                tokenStore = object : com.opengpt.auth.TokenStore {
                    override fun getToken() = token

                    override fun save(token: OAuthToken) {}

                    override fun clear() {}
                },
                oauthService =
                    com.opengpt.auth.OAuthService(
                        properties =
                            com.opengpt.config.OAuthProperties(
                                clientId = "test",
                                issuer = "http://localhost",
                                callbackUrl = "http://localhost/callback",
                            ),
                        pkceGenerator = com.opengpt.auth.PkceGenerator(),
                        jwtParser = com.opengpt.auth.JwtParser(ObjectMapper()),
                        tokenStore =
                            object : com.opengpt.auth.TokenStore {
                                override fun getToken() = token

                                override fun save(token: OAuthToken) {}

                                override fun clear() {}
                            },
                        webClientBuilder = WebClient.builder(),
                    ),
            ) {
                override fun getValidToken(): OAuthToken = token
            }

        val client =
            CodexClient(
                CodexProperties(
                    endpoint = "http://localhost:${server.port()}/backend-api/codex/responses",
                    userAgent = "opengpt",
                    originator = "opengpt",
                ),
                refreshService,
                WebClient.builder(),
            )

        val body = ObjectMapper().readTree("""{"model":"gpt-5.4","input":[],"stream":true}""")
        val text =
            DataBufferUtils.join(client.streamResponse(body))
                .map {
                    val value = it.toString(StandardCharsets.UTF_8)
                    DataBufferUtils.release(it)
                    value
                }
                .block()

        assertTrue(text!!.contains("response.completed"))
        server.verify(
            postRequestedFor(urlEqualTo("/backend-api/codex/responses"))
                .withHeader("Authorization", equalTo("Bearer access-token"))
                .withHeader("ChatGPT-Account-Id", equalTo("acc-1"))
                .withHeader("x-openai-internal-codex-residency", equalTo("eu"))
                .withHeader("originator", equalTo("opengpt")),
        )
    }
}
