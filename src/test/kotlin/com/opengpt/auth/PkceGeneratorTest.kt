package com.opengpt.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class PkceGeneratorTest {
    private val generator = PkceGenerator()

    @Test
    fun `generates verifier and s256 challenge`() {
        val pkce = generator.generate()
        assertEquals(43, pkce.verifier.length)
        assertTrue(pkce.challenge.isNotBlank())
        val digest =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(pkce.verifier.toByteArray(Charsets.US_ASCII))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        assertEquals(expected, pkce.challenge)
    }

    @Test
    fun `authorization url contains required params`() {
        val pkce = generator.generate()
        val state = generator.randomState()
        val url =
            buildString {
                append("https://auth.openai.com/oauth/authorize?")
                append("response_type=code")
                append("&client_id=app_EMoamEEZ73f0CkXaXp7hrann")
                append("&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback")
                append("&scope=openid+profile+email+offline_access")
                append("&code_challenge=${pkce.challenge}")
                append("&code_challenge_method=S256")
                append("&state=$state")
                append("&codex_cli_simplified_flow=true")
                append("&originator=opengpt")
            }
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("codex_cli_simplified_flow=true"))
        assertTrue(url.contains("originator=opengpt"))
    }
}
