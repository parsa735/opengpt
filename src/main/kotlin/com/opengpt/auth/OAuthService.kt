package com.opengpt.auth

import com.opengpt.config.OAuthProperties
import com.opengpt.model.OAuthToken
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

data class PendingOAuth(
    val pkce: PkceCodes,
    val state: String,
    val createdAt: Instant = Instant.now(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String,
    @JsonProperty("id_token") val idToken: String? = null,
    @JsonProperty("expires_in") val expiresIn: Long? = 3600,
)

@Service
class OAuthService(
    private val properties: OAuthProperties,
    private val pkceGenerator: PkceGenerator,
    private val jwtParser: JwtParser,
    private val tokenStore: TokenStore,
    webClientBuilder: WebClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webClient = webClientBuilder.build()
    private val pending = AtomicReference<PendingOAuth?>(null)

    fun buildAuthorizationUrl(): String {
        val pkce = pkceGenerator.generate()
        val state = pkceGenerator.randomState()
        pending.set(PendingOAuth(pkce = pkce, state = state))
        log.info("OAuth login started")

        val params =
            LinkedMultiValueMap<String, String>().apply {
                add("response_type", "code")
                add("client_id", properties.clientId)
                add("redirect_uri", properties.callbackUrl)
                add("scope", properties.scope)
                add("code_challenge", pkce.challenge)
                add("code_challenge_method", "S256")
                add("id_token_add_organizations", "true")
                add("codex_cli_simplified_flow", "true")
                add("state", state)
                add("originator", properties.originator)
            }

        val query =
            params.entries.joinToString("&") { (key, values) ->
                "$key=${java.net.URLEncoder.encode(values.first(), Charsets.UTF_8)}"
            }
        return "${properties.issuer.trimEnd('/')}/oauth/authorize?$query"
    }

    fun handleCallback(code: String, state: String): OAuthToken {
        val current =
            pending.getAndSet(null)
                ?: throw IllegalStateException("No pending OAuth login")
        if (current.state != state) {
            throw IllegalStateException("Invalid OAuth state")
        }

        val tokens = exchangeCode(code, current.pkce.verifier)
        val saved = toStoredToken(tokens)
        tokenStore.save(saved)
        log.info("OAuth login completed")
        return tokenStore.getToken() ?: saved
    }

    fun refresh(refreshToken: String): OAuthToken {
        log.info("Token refresh started")
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "refresh_token")
                add("refresh_token", refreshToken)
                add("client_id", properties.clientId)
            }
        val tokens =
            webClient
                .post()
                .uri("${properties.issuer.trimEnd('/')}/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono<TokenResponse>()
                .block()
                ?: throw IllegalStateException("Token refresh returned empty body")
        val existingApiKey = tokenStore.getToken()?.apiKey
        val saved =
            toStoredToken(tokens, fallbackRefresh = refreshToken)
                .copy(apiKey = existingApiKey)
        tokenStore.save(saved)
        log.info("Token refreshed")
        return saved
    }

    private fun exchangeCode(code: String, verifier: String): TokenResponse {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("code", code)
                add("redirect_uri", properties.callbackUrl)
                add("client_id", properties.clientId)
                add("code_verifier", verifier)
            }
        return webClient
            .post()
            .uri("${properties.issuer.trimEnd('/')}/oauth/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .retrieve()
            .bodyToMono<TokenResponse>()
            .block()
            ?: throw IllegalStateException("Token exchange returned empty body")
    }

    private fun toStoredToken(tokens: TokenResponse, fallbackRefresh: String? = null): OAuthToken {
        val accountId = jwtParser.extractAccountId(tokens.idToken, tokens.accessToken)
        val residency = jwtParser.extractResidency(tokens.accessToken)
        return OAuthToken(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.ifBlank { fallbackRefresh.orEmpty() },
            expiresAt = System.currentTimeMillis() + (tokens.expiresIn ?: 3600) * 1000,
            accountId = accountId,
            residency = residency,
        )
    }
}
