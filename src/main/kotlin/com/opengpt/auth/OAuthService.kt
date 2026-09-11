package com.opengpt.auth

import com.opengpt.config.OAuthProperties
import com.opengpt.model.OAuthToken
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

data class PendingOAuth(
    val pkce: PkceCodes,
    val state: String,
    /** Client-facing origin to link back to after callback (e.g. ngrok URL). */
    val returnUrl: String? = null,
    val createdAt: Instant = Instant.now(),
)

data class OAuthLoginResult(
    val token: OAuthToken,
    val returnUrl: String? = null,
)

data class PendingDeviceCode(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Long,
    val expiresAt: Instant,
)

data class DeviceCodeStart(
    val verificationUrl: String,
    val userCode: String,
    val intervalSeconds: Long,
    val expiresInSeconds: Long,
)

sealed class DeviceCodePollResult {
    data object NoPending : DeviceCodePollResult()

    data object Pending : DeviceCodePollResult()

    data object Expired : DeviceCodePollResult()

    data class Completed(val token: OAuthToken) : DeviceCodePollResult()

    data class Failed(val message: String) : DeviceCodePollResult()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String,
    @JsonProperty("id_token") val idToken: String? = null,
    @JsonProperty("expires_in") val expiresIn: Long? = 3600,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCodeUserResponse(
    @JsonProperty("device_auth_id") val deviceAuthId: String,
    @JsonProperty("user_code") val userCode: String? = null,
    @JsonProperty("usercode") val userCodeAlias: String? = null,
    val interval: JsonNode? = null,
) {
    fun resolvedUserCode(): String =
        userCode?.takeIf { it.isNotBlank() }
            ?: userCodeAlias?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Device code response missing user_code")

    fun resolvedIntervalSeconds(): Long {
        val node = interval
        val raw =
            when {
                node == null || node.isNull || node.isMissingNode -> null
                node.isNumber -> node.longValue()
                node.isString -> node.stringValue()?.trim()?.toLongOrNull()
                else -> node.asString("").trim().toLongOrNull()
            }
        return when {
            raw == null || raw <= 0L -> OAuthService.DEFAULT_DEVICE_POLL_INTERVAL_SECONDS
            else -> raw
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCodeTokenSuccess(
    @JsonProperty("authorization_code") val authorizationCode: String,
    @JsonProperty("code_verifier") val codeVerifier: String,
    @JsonProperty("code_challenge") val codeChallenge: String? = null,
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
    private val pendingDevice = AtomicReference<PendingDeviceCode?>(null)

    fun buildAuthorizationUrl(returnUrl: String? = null): String {
        pendingDevice.set(null)
        val pkce = pkceGenerator.generate()
        val state = pkceGenerator.randomState()
        pending.set(PendingOAuth(pkce = pkce, state = state, returnUrl = returnUrl))
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

    fun handleCallback(code: String, state: String): OAuthLoginResult {
        val current =
            pending.getAndSet(null)
                ?: throw IllegalStateException("No pending OAuth login")
        if (current.state != state) {
            throw IllegalStateException("Invalid OAuth state")
        }

        val tokens = exchangeCode(code, current.pkce.verifier, properties.callbackUrl)
        val saved = toStoredToken(tokens)
        tokenStore.save(saved)
        log.info("OAuth login completed")
        return OAuthLoginResult(
            token = tokenStore.getToken() ?: saved,
            returnUrl = current.returnUrl,
        )
    }

    fun startDeviceCodeLogin(): DeviceCodeStart {
        pending.set(null)
        val issuer = properties.issuer.trimEnd('/')
        val response =
            webClient
                .post()
                .uri("$issuer/api/accounts/deviceauth/usercode")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("client_id" to properties.clientId))
                .exchangeToMono { clientResponse ->
                    val status = clientResponse.statusCode()
                    when {
                        status.is2xxSuccessful ->
                            clientResponse.bodyToMono<DeviceCodeUserResponse>()
                        status.value() == 404 ->
                            clientResponse.bodyToMono<String>().defaultIfEmpty("").flatMap {
                                Mono.error(
                                    DeviceCodeNotEnabledException(
                                        "Device code login is not enabled for this Codex server. " +
                                            "Enable it in ChatGPT Security settings, or use browser login.",
                                    ),
                                )
                            }
                        else ->
                            clientResponse.bodyToMono<String>().defaultIfEmpty("").flatMap { body ->
                                Mono.error(
                                    IllegalStateException(
                                        "Device code request failed with status ${status.value()}" +
                                            if (body.isNotBlank()) ": $body" else "",
                                    ),
                                )
                            }
                    }
                }.block()
                ?: throw IllegalStateException("Device code request returned empty body")

        val intervalSeconds = response.resolvedIntervalSeconds()
        val userCode = response.resolvedUserCode()
        pendingDevice.set(
            PendingDeviceCode(
                deviceAuthId = response.deviceAuthId,
                userCode = userCode,
                intervalSeconds = intervalSeconds,
                expiresAt = Instant.now().plusSeconds(DEVICE_CODE_TIMEOUT_SECONDS),
            ),
        )
        log.info("Device code login started")
        return DeviceCodeStart(
            verificationUrl = "$issuer/codex/device",
            userCode = userCode,
            intervalSeconds = intervalSeconds,
            expiresInSeconds = DEVICE_CODE_TIMEOUT_SECONDS,
        )
    }

    fun pollDeviceCodeLogin(): DeviceCodePollResult {
        val current = pendingDevice.get() ?: return DeviceCodePollResult.NoPending
        if (Instant.now().isAfter(current.expiresAt)) {
            pendingDevice.compareAndSet(current, null)
            return DeviceCodePollResult.Expired
        }

        val issuer = properties.issuer.trimEnd('/')
        return try {
            val pollOutcome =
                webClient
                    .post()
                    .uri("$issuer/api/accounts/deviceauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                        mapOf(
                            "device_auth_id" to current.deviceAuthId,
                            "user_code" to current.userCode,
                        ),
                    ).exchangeToMono { clientResponse ->
                        interpretDevicePollResponse(clientResponse.statusCode(), clientResponse)
                    }.block()
                    ?: DevicePollOutcome.Pending

            when (pollOutcome) {
                DevicePollOutcome.Pending -> DeviceCodePollResult.Pending
                is DevicePollOutcome.Failed -> DeviceCodePollResult.Failed(pollOutcome.message)
                is DevicePollOutcome.Authorized -> {
                    if (!pendingDevice.compareAndSet(current, null)) {
                        return DeviceCodePollResult.NoPending
                    }
                    val tokens =
                        exchangeCode(
                            pollOutcome.authorizationCode,
                            pollOutcome.codeVerifier,
                            deviceCallbackUrl(),
                        )
                    val saved = toStoredToken(tokens)
                    tokenStore.save(saved)
                    log.info("Device code login completed")
                    DeviceCodePollResult.Completed(tokenStore.getToken() ?: saved)
                }
            }
        } catch (error: Exception) {
            log.warn("Device code poll failed: {}", error.message)
            DeviceCodePollResult.Failed(error.message ?: "Device code poll failed")
        }
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

    private fun interpretDevicePollResponse(
        status: HttpStatusCode,
        clientResponse: org.springframework.web.reactive.function.client.ClientResponse,
    ): Mono<DevicePollOutcome> {
        val code = status.value()
        if (status.is2xxSuccessful) {
            return clientResponse.bodyToMono<DeviceCodeTokenSuccess>().map { body ->
                if (body.authorizationCode.isBlank() || body.codeVerifier.isBlank()) {
                    DevicePollOutcome.Failed("Device auth token response missing authorization fields")
                } else {
                    DevicePollOutcome.Authorized(body.authorizationCode, body.codeVerifier)
                }
            }
        }
        if (code == 403 || code == 404) {
            return clientResponse.bodyToMono<String>().defaultIfEmpty("").map { DevicePollOutcome.Pending }
        }
        return clientResponse.bodyToMono<String>().defaultIfEmpty("").map { body ->
            val errorCode = extractDeviceAuthErrorCode(body)
            when (errorCode) {
                "deviceauth_authorization_pending", "authorization_pending" -> DevicePollOutcome.Pending
                "slow_down" -> DevicePollOutcome.Pending
                else ->
                    DevicePollOutcome.Failed(
                        "Device auth failed with status $code" +
                            if (body.isNotBlank()) ": $body" else "",
                    )
            }
        }
    }

    private fun extractDeviceAuthErrorCode(body: String): String? {
        if (body.isBlank()) return null
        return try {
            // Lightweight parse without injecting ObjectMapper into every call site.
            val matcher = Regex("\"(?:code|error)\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
            matcher.map { it.groupValues[1] }.firstOrNull { it.startsWith("deviceauth_") || it in KNOWN_PENDING_CODES }
                ?: matcher.map { it.groupValues[1] }.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun exchangeCode(code: String, verifier: String, redirectUri: String): TokenResponse {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("code", code)
                add("redirect_uri", redirectUri)
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

    private fun deviceCallbackUrl(): String = "${properties.issuer.trimEnd('/')}/deviceauth/callback"

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

    private sealed class DevicePollOutcome {
        data object Pending : DevicePollOutcome()

        data class Authorized(
            val authorizationCode: String,
            val codeVerifier: String,
        ) : DevicePollOutcome()

        data class Failed(val message: String) : DevicePollOutcome()
    }

    companion object {
        const val DEVICE_CODE_TIMEOUT_SECONDS = 15L * 60L
        const val DEFAULT_DEVICE_POLL_INTERVAL_SECONDS = 5L
        private val KNOWN_PENDING_CODES = setOf("authorization_pending", "slow_down")
    }
}

class DeviceCodeNotEnabledException(message: String) : IllegalStateException(message)
