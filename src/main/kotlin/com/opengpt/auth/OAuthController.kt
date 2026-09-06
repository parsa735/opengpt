package com.opengpt.auth

import com.opengpt.config.AdapterProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.view.RedirectView

@Controller
class OAuthController(
    private val oauthService: OAuthService,
    private val tokenStore: TokenStore,
    private val callbackServer: OAuthCallbackServer,
    private val adapterProperties: AdapterProperties,
) {
    @GetMapping("/auth/login")
    fun login(): RedirectView {
        callbackServer.ensureStarted()
        return RedirectView(oauthService.buildAuthorizationUrl())
    }

    @GetMapping("/auth/callback")
    @ResponseBody
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "error_description", required = false) errorDescription: String?,
    ): String {
        if (!error.isNullOrBlank()) {
            val message = errorDescription ?: error
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
        }
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing authorization code or state")
        }
        val token = oauthService.handleCallback(code, state)
        return successHtml(token.apiKey.orEmpty())
    }

    @PostMapping("/auth/regenerate-key")
    fun regenerateKey(): RedirectView {
        if (!tokenStore.isAuthenticated()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated")
        }
        tokenStore.regenerateApiKey()
        return RedirectView("/")
    }

    @GetMapping("/auth/status")
    @ResponseBody
    fun status(): Map<String, Any?> {
        val token = tokenStore.getToken()
        return mapOf(
            "authenticated" to (token != null),
            "accountId" to token?.accountId,
            "expiresAt" to token?.expiresAt,
            "apiKeyConfigured" to !token?.apiKey.isNullOrBlank(),
        )
    }

    @GetMapping(value = ["/", "/index.html"], produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun home(): String {
        val home = adapterProperties.publicBaseUrl.trimEnd('/')
        val token = tokenStore.getToken()
        val authenticated = token != null
        val apiKey = token?.apiKey.orEmpty()

        val body =
            if (authenticated) {
                """
                <p class="ok">Authenticated with ChatGPT.</p>
                <div class="card">
                  <label>Base URL</label>
                  <div class="row">
                    <input id="baseUrl" readonly value="$home/v1"/>
                    <button type="button" onclick="copyField('baseUrl')">Copy</button>
                  </div>
                  <label>API Key</label>
                  <div class="row">
                    <input id="apiKey" readonly value="$apiKey"/>
                    <button type="button" onclick="copyField('apiKey')">Copy</button>
                  </div>
                  <p class="hint">Paste both values into Cursor → Settings → Models → OpenAI API Key + Override OpenAI Base URL.</p>
                  <p class="warn">Cursor often blocks <code>localhost</code> ("Access to private networks is forbidden"). If that happens, expose this adapter with a public HTTPS tunnel (e.g. Cloudflare Tunnel / ngrok) and use that URL as the base URL instead.</p>
                </div>
                <form method="post" action="/auth/regenerate-key">
                  <button type="submit" class="secondary">Regenerate API key</button>
                </form>
                """.trimIndent()
            } else {
                """
                <p>Not authenticated.</p>
                <p class="hint">Login opens OpenAI OAuth and returns to <code>http://localhost:1455/auth/callback</code> (Codex-registered redirect).</p>
                """.trimIndent()
            }

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"/>
              <title>OpenGPT</title>
              <style>
                body { font-family: system-ui, sans-serif; max-width: 44rem; margin: 3rem auto; padding: 0 1rem; color: #1a1a1a; }
                a.button, button { display: inline-block; background: #111; color: #fff; padding: .65rem 1.1rem; border-radius: .5rem; text-decoration: none; border: 0; cursor: pointer; font: inherit; }
                button.secondary, .secondary { background: #444; margin-top: 1rem; }
                .ok { color: #0a7a32; }
                .hint, .warn { color: #555; font-size: .95rem; }
                .warn { color: #8a5a00; }
                .card { background: #f6f6f6; padding: 1rem; border-radius: .75rem; margin: 1rem 0; }
                label { display: block; font-weight: 600; margin: .75rem 0 .35rem; }
                .row { display: flex; gap: .5rem; }
                input { flex: 1; font-family: ui-monospace, monospace; padding: .55rem .65rem; border: 1px solid #ccc; border-radius: .4rem; }
                code { background: #eee; padding: .1rem .35rem; border-radius: .25rem; }
                #toast { display:none; margin-top:.75rem; color:#0a7a32; font-weight:600; }
              </style>
            </head>
            <body>
              <h1>OpenGPT</h1>
              $body
              <p><a class="button" href="/auth/login">${if (authenticated) "Re-login with OpenAI" else "Login with OpenAI"}</a></p>
              <div id="toast">Copied</div>
              <script>
                function copyField(id) {
                  const el = document.getElementById(id);
                  navigator.clipboard.writeText(el.value).then(() => {
                    const toast = document.getElementById('toast');
                    toast.style.display = 'block';
                    setTimeout(() => toast.style.display = 'none', 1200);
                  });
                }
              </script>
            </body>
            </html>
            """.trimIndent()
    }

    private fun successHtml(apiKey: String): String {
        val home = adapterProperties.publicBaseUrl.trimEnd('/')
        return """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"/><title>Authentication successful</title></head>
            <body style="font-family:system-ui;max-width:44rem;margin:4rem auto;padding:0 1rem">
              <h1>Authentication successful</h1>
              <p>Use these values in Cursor:</p>
              <p>Base URL: <code>$home/v1</code></p>
              <p>API Key: <code>$apiKey</code></p>
              <p><a href="$home/">Open adapter home</a> to copy them with one click.</p>
            </body>
            </html>
            """.trimIndent()
    }
}
