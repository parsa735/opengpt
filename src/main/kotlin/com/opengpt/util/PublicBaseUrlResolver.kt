package com.opengpt.util

import jakarta.servlet.http.HttpServletRequest
import java.net.URI

/**
 * Resolves the client-facing base URL for UI links and Cursor configuration.
 *
 * Behind ngrok / Cloudflare Tunnel the bind address is still loopback, but the
 * browser Host / X-Forwarded-* headers carry the public URL users must copy.
 */
object PublicBaseUrlResolver {
    fun fromRequest(request: HttpServletRequest, fallback: String): String {
        val forwardedHost = firstForwardedValue(request.getHeader("X-Forwarded-Host"))
        val hostHeader = forwardedHost ?: request.getHeader("Host")?.trim().orEmpty()
        if (hostHeader.isBlank()) {
            return normalize(fallback)
        }

        val forwardedProto = firstForwardedValue(request.getHeader("X-Forwarded-Proto"))
        val scheme =
            (forwardedProto ?: request.scheme ?: "http")
                .trim()
                .lowercase()
                .ifBlank { "http" }

        return normalize("$scheme://$hostHeader")
    }

    fun sanitize(url: String?, fallback: String): String {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return normalize(fallback)
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
                normalize(fallback)
            } else {
                val port =
                    when {
                        uri.port < 0 -> ""
                        scheme == "http" && uri.port == 80 -> ""
                        scheme == "https" && uri.port == 443 -> ""
                        else -> ":${uri.port}"
                    }
                normalize("$scheme://$host$port")
            }
        } catch (_: Exception) {
            normalize(fallback)
        }
    }

    fun isLoopbackHost(hostOrUrl: String): Boolean {
        val host =
            try {
                val uri = URI(if ("://" in hostOrUrl) hostOrUrl else "http://$hostOrUrl")
                uri.host ?: hostOrUrl.substringBefore(':').substringBefore('/')
            } catch (_: Exception) {
                hostOrUrl.substringBefore(':').substringBefore('/')
            }.trim()
                .lowercase()
                .removePrefix("[")
                .removeSuffix("]")
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host.endsWith(".localhost")
    }

    fun normalize(url: String): String = url.trim().trimEnd('/')

    fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun firstForwardedValue(header: String?): String? =
        header
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
