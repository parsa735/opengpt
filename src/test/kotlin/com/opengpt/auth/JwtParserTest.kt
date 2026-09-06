package com.opengpt.auth

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

class JwtParserTest {
    private val parser = JwtParser(jacksonObjectMapper())

    @Test
    fun `extracts chatgpt_account_id from root`() {
        val token = jwt(mapOf("chatgpt_account_id" to "acc-root"))
        assertEquals("acc-root", parser.extractAccountId(token, null))
    }

    @Test
    fun `extracts nested openai auth account id`() {
        val token =
            jwt(
                mapOf(
                    "https://api.openai.com/auth" to mapOf("chatgpt_account_id" to "acc-nested"),
                ),
            )
        assertEquals("acc-nested", parser.extractAccountId(token, null))
    }

    @Test
    fun `falls back to organizations`() {
        val token = jwt(mapOf("organizations" to listOf(mapOf("id" to "org-1"))))
        assertEquals("org-1", parser.extractAccountId(token, null))
    }

    @Test
    fun `ignores no_constraint residency`() {
        val token =
            jwt(
                mapOf(
                    "https://api.openai.com/auth" to mapOf("chatgpt_compute_residency" to "no_constraint"),
                ),
            )
        assertNull(parser.extractResidency(token))
    }

    @Test
    fun `extracts residency`() {
        val token =
            jwt(
                mapOf(
                    "https://api.openai.com/auth" to mapOf("chatgpt_compute_residency" to "eu"),
                ),
            )
        assertEquals("eu", parser.extractResidency(token))
    }

    private fun jwt(payload: Map<String, Any?>): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val body =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ObjectMapper().writeValueAsBytes(payload))
        return "$header.$body.sig"
    }
}
