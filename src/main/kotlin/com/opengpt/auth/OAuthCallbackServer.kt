package com.opengpt.auth

import com.opengpt.config.AdapterProperties
import com.opengpt.config.OAuthProperties
import com.sun.net.httpserver.HttpServer
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Loopback OAuth callback listener on the Codex-registered redirect port (1455).
 *
 * The ChatGPT/Codex OAuth client id only accepts
 * `http://localhost:1455/auth/callback` — not the main API port.
 */
@Component
class OAuthCallbackServer(
    private val oauthProperties: OAuthProperties,
    private val adapterProperties: AdapterProperties,
    private val oauthService: OAuthService,
    private val tokenStore: TokenStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private var server: HttpServer? = null

    fun ensureStarted() {
        synchronized(lock) {
            if (server != null) return
            val port = oauthProperties.callbackPort
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            httpServer.createContext("/auth/callback") { exchange ->
                val query = exchange.requestURI.rawQuery.orEmpty()
                val params = parseQuery(query)
                val html =
                    try {
                        val error = params["error_description"] ?: params["error"]
                        if (!error.isNullOrBlank()) {
                            errorPage(error)
                        } else {
                            val code = params["code"]
                            val state = params["state"]
                            if (code.isNullOrBlank() || state.isNullOrBlank()) {
                                errorPage("Missing authorization code or state")
                            } else {
                                val token = oauthService.handleCallback(code, state)
                                successPage(token.apiKey ?: tokenStore.getApiKey().orEmpty())
                            }
                        }
                    } catch (error: Exception) {
                        log.warn("OAuth callback failed: {}", error.message)
                        errorPage(error.message ?: "OAuth callback failed")
                    }

                val bytes = html.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            httpServer.executor = Executors.newCachedThreadPool()
            httpServer.start()
            server = httpServer
            log.info("OAuth callback server listening on http://127.0.0.1:{}/auth/callback", port)
        }
    }

    @PreDestroy
    fun stop() {
        synchronized(lock) {
            server?.stop(0)
            server = null
        }
    }

    private fun successPage(apiKey: String): String {
        val home = adapterProperties.publicBaseUrl.trimEnd('/')
        return """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"/><title>Authentication successful</title></head>
            <body style="font-family:system-ui;max-width:44rem;margin:4rem auto;padding:0 1rem">
              <h1>Authentication successful</h1>
              <p>You can configure your AI client.</p>
              <p>Base URL: <code>$home/v1</code></p>
              <p>API Key: <code>$apiKey</code></p>
              <p><a href="$home/">Back to adapter</a> (copy buttons on the home page)</p>
            </body>
            </html>
            """.trimIndent()
    }

    private fun errorPage(message: String): String =
        """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"/><title>Authorization failed</title></head>
        <body style="font-family:system-ui;max-width:40rem;margin:4rem auto;padding:0 1rem">
          <h1>Authorization failed</h1>
          <p>${escape(message)}</p>
          <p><a href="${adapterProperties.publicBaseUrl.trimEnd('/')}">Back</a></p>
        </body>
        </html>
        """.trimIndent()

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) return@mapNotNull null
            val key = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8)
            key to value
        }.toMap()
    }
}
