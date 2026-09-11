package com.opengpt.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class PublicBaseUrlResolverTest {
    @Test
    fun `uses host and scheme from the request`() {
        val request = MockHttpServletRequest("GET", "/")
        request.scheme = "http"
        request.setServerName("example.internal")
        request.setServerPort(5080)
        request.addHeader("Host", "example.internal:5080")

        assertEquals(
            "http://example.internal:5080",
            PublicBaseUrlResolver.fromRequest(request, "http://localhost:5080"),
        )
    }

    @Test
    fun `prefers forwarded proto and host for tunnels`() {
        val request = MockHttpServletRequest("GET", "/")
        request.scheme = "http"
        request.setServerName("127.0.0.1")
        request.setServerPort(5080)
        request.addHeader("Host", "127.0.0.1:5080")
        request.addHeader("X-Forwarded-Proto", "https")
        request.addHeader("X-Forwarded-Host", "abc123.ngrok-free.app")

        assertEquals(
            "https://abc123.ngrok-free.app",
            PublicBaseUrlResolver.fromRequest(request, "http://localhost:5080"),
        )
    }

    @Test
    fun `falls back when host headers are missing`() {
        val request = MockHttpServletRequest("GET", "/")
        assertEquals(
            "http://localhost:5080",
            PublicBaseUrlResolver.fromRequest(request, "http://localhost:5080/"),
        )
    }

    @Test
    fun `sanitize rejects non-http schemes`() {
        assertEquals(
            "http://localhost:5080",
            PublicBaseUrlResolver.sanitize("javascript:alert(1)", "http://localhost:5080"),
        )
    }

    @Test
    fun `detects loopback hosts`() {
        assertTrue(PublicBaseUrlResolver.isLoopbackHost("http://localhost:5080"))
        assertTrue(PublicBaseUrlResolver.isLoopbackHost("http://127.0.0.1:5080"))
        assertFalse(PublicBaseUrlResolver.isLoopbackHost("https://abc123.ngrok-free.app"))
    }
}
