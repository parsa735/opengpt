package com.opengpt.auth

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.Base64

@Component
class JwtParser(
    private val objectMapper: ObjectMapper,
) {
    fun parseClaims(token: String): JsonNode? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(parts[1]))
            objectMapper.readTree(json)
        }.getOrNull()
    }

    fun extractAccountId(idToken: String?, accessToken: String?): String? {
        idToken?.let { extractAccountIdFromToken(it) }?.let { return it }
        return accessToken?.let { extractAccountIdFromToken(it) }
    }

    fun extractResidency(accessToken: String): String? {
        val claims = parseClaims(accessToken) ?: return null
        val residency =
            claims.path("https://api.openai.com/auth").path("chatgpt_compute_residency").asText(null)
                ?: claims.path("chatgpt_compute_residency").asText(null)
        if (residency.isNullOrBlank() || residency == "no_constraint") return null
        return residency
    }

    private fun extractAccountIdFromToken(token: String): String? {
        val claims = parseClaims(token) ?: return null
        claims.path("chatgpt_account_id").asText(null)?.takeIf { it.isNotBlank() }?.let { return it }
        claims.path("https://api.openai.com/auth").path("chatgpt_account_id").asText(null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val orgs = claims.path("organizations")
        if (orgs.isArray && orgs.size() > 0) {
            return orgs[0].path("id").asText(null)?.takeIf { it.isNotBlank() }
        }
        return null
    }
}
