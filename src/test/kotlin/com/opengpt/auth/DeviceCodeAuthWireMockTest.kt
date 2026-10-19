package com.opengpt.auth

import com.opengpt.config.OAuthProperties
import com.opengpt.model.OAuthToken
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicReference

class DeviceCodeAuthWireMockTest {
    private lateinit var server: WireMockServer
    private lateinit var saved: AtomicReference<OAuthToken?>
    private lateinit var oauthService: OAuthService

    @BeforeEach
    fun start() {
        server = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        server.start()
        saved = AtomicReference(null)
        val store =
            object : TokenStore {
                override fun getToken(): OAuthToken? = saved.get()

                override fun save(token: OAuthToken) {
                    val existingKey = saved.get()?.apiKey
                    saved.set(
                        if (token.apiKey.isNullOrBlank() && !existingKey.isNullOrBlank()) {
                            token.copy(apiKey = existingKey)
                        } else if (token.apiKey.isNullOrBlank()) {
                            token.copy(apiKey = "sk-cla-test")
                        } else {
                            token
                        },
                    )
                }

                override fun clear() {
                    saved.set(null)
                }

                override fun isAuthenticated(): Boolean = saved.get() != null

                override fun getApiKey(): String? = saved.get()?.apiKey

                override fun regenerateApiKey(): String {
                    val next = "sk-cla-regenerated"
                    saved.updateAndGet { it?.copy(apiKey = next) }
                    return next
                }
            }
        oauthService =
            OAuthService(
                properties =
                    OAuthProperties(
                        clientId = "app_test_client",
                        issuer = server.baseUrl(),
                        callbackUrl = "http://localhost:1455/auth/callback",
                    ),
                pkceGenerator = PkceGenerator(),
                jwtParser = JwtParser(ObjectMapper()),
                tokenStore = store,
                webClientBuilder = WebClient.builder(),
            )
    }

    @AfterEach
    fun stop() {
        server.stop()
    }

    @Test
    fun `starts device code login and returns verification details`() {
        server.stubFor(
            post(urlEqualTo("/api/accounts/deviceauth/usercode"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {"device_auth_id":"auth-1","user_code":"ABCD-EFGH","interval":"5"}
                            """.trimIndent(),
                        ),
                ),
        )

        val started = oauthService.startDeviceCodeLogin()

        assertEquals("${server.baseUrl()}/codex/device", started.verificationUrl)
        assertEquals("ABCD-EFGH", started.userCode)
        assertEquals(5L, started.intervalSeconds)
        assertEquals(OAuthService.DEVICE_CODE_TIMEOUT_SECONDS, started.expiresInSeconds)
        server.verify(
            postRequestedFor(urlEqualTo("/api/accounts/deviceauth/usercode"))
                .withRequestBody(containing("app_test_client")),
        )
    }

    @Test
    fun `returns not-enabled when device code endpoint is missing`() {
        server.stubFor(
            post(urlEqualTo("/api/accounts/deviceauth/usercode"))
                .willReturn(aResponse().withStatus(404).withBody("missing")),
        )

        assertThrows(DeviceCodeNotEnabledException::class.java) {
            oauthService.startDeviceCodeLogin()
        }
    }

    @Test
    fun `polls until authorized then exchanges tokens with device callback`() {
        server.stubFor(
            post(urlEqualTo("/api/accounts/deviceauth/usercode"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"device_auth_id":"auth-1","user_code":"ABCD-EFGH","interval":2}""",
                        ),
                ),
        )
        server.stubFor(
            post(urlEqualTo("/api/accounts/deviceauth/token"))
                .inScenario("device-poll")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(403).withBody("""{"error":"deviceauth_authorization_pending"}"""))
                .willSetStateTo("approved"),
        )
        server.stubFor(
            post(urlEqualTo("/api/accounts/deviceauth/token"))
                .inScenario("device-poll")
                .whenScenarioStateIs("approved")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "authorization_code":"auth-code-1",
                              "code_verifier":"verifier-1",
                              "code_challenge":"challenge-1"
                            }
                            """.trimIndent(),
                        ),
                ),
        )
        server.stubFor(
            post(urlEqualTo("/oauth/token"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "access_token":"access-1",
                              "refresh_token":"refresh-1",
                              "expires_in":3600
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        oauthService.startDeviceCodeLogin()
        assertInstanceOf(DeviceCodePollResult.Pending::class.java, oauthService.pollDeviceCodeLogin())

        val completed = oauthService.pollDeviceCodeLogin()
        assertInstanceOf(DeviceCodePollResult.Completed::class.java, completed)
        val token = (completed as DeviceCodePollResult.Completed).token
        assertEquals("access-1", token.accessToken)
        assertEquals("refresh-1", token.refreshToken)
        assertTrue(!token.apiKey.isNullOrBlank())

        server.verify(
            postRequestedFor(urlEqualTo("/oauth/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=auth-code-1"))
                .withRequestBody(containing("code_verifier=verifier-1"))
                .withRequestBody(containing("deviceauth%2Fcallback")),
        )
        assertInstanceOf(DeviceCodePollResult.NoPending::class.java, oauthService.pollDeviceCodeLogin())
    }

    @Test
    fun `poll without start returns no pending`() {
        assertEquals(DeviceCodePollResult.NoPending, oauthService.pollDeviceCodeLogin())
    }
}
