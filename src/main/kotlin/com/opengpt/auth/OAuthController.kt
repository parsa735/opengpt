package com.opengpt.auth

import com.opengpt.config.AdapterProperties
import com.opengpt.util.PublicBaseUrlResolver
import jakarta.servlet.http.HttpServletRequest
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
    fun login(request: HttpServletRequest): RedirectView {
        callbackServer.ensureStarted()
        val returnUrl =
            PublicBaseUrlResolver.fromRequest(request, adapterProperties.publicBaseUrl)
        return RedirectView(oauthService.buildAuthorizationUrl(returnUrl))
    }

    @PostMapping("/auth/device/start", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun startDeviceCode(): org.springframework.http.ResponseEntity<Map<String, Any?>> {
        return try {
            val started = oauthService.startDeviceCodeLogin()
            org.springframework.http.ResponseEntity.ok(
                mapOf(
                    "verificationUrl" to started.verificationUrl,
                    "userCode" to started.userCode,
                    "intervalSeconds" to started.intervalSeconds,
                    "expiresInSeconds" to started.expiresInSeconds,
                ),
            )
        } catch (error: DeviceCodeNotEnabledException) {
            org.springframework.http.ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf(
                    "status" to "error",
                    "message" to (error.message ?: "Device code login is not enabled"),
                ),
            )
        } catch (error: IllegalStateException) {
            org.springframework.http.ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                mapOf(
                    "status" to "error",
                    "message" to (error.message ?: "Device code login failed"),
                ),
            )
        }
    }

    @PostMapping("/auth/device/poll", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun pollDeviceCode(): Map<String, Any?> {
        return when (val result = oauthService.pollDeviceCodeLogin()) {
            DeviceCodePollResult.NoPending ->
                mapOf("status" to "no_pending")
            DeviceCodePollResult.Pending ->
                mapOf("status" to "pending")
            DeviceCodePollResult.Expired ->
                mapOf("status" to "expired")
            is DeviceCodePollResult.Completed ->
                mapOf(
                    "status" to "completed",
                    "authenticated" to true,
                    "accountId" to result.token.accountId,
                )
            is DeviceCodePollResult.Failed ->
                mapOf(
                    "status" to "error",
                    "message" to result.message,
                )
        }
    }

    @GetMapping("/auth/callback")
    @ResponseBody
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "error_description", required = false) errorDescription: String?,
        request: HttpServletRequest,
    ): String {
        if (!error.isNullOrBlank()) {
            val message = errorDescription ?: error
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
        }
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing authorization code or state")
        }
        val result = oauthService.handleCallback(code, state)
        val home =
            PublicBaseUrlResolver.sanitize(
                result.returnUrl,
                PublicBaseUrlResolver.fromRequest(request, adapterProperties.publicBaseUrl),
            )
        return successHtml(result.token.apiKey.orEmpty(), home)
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
    fun home(request: HttpServletRequest): String {
        val home =
            PublicBaseUrlResolver.fromRequest(request, adapterProperties.publicBaseUrl)
        val remoteAccess = !PublicBaseUrlResolver.isLoopbackHost(home)
        val token = tokenStore.getToken()
        val authenticated = token != null
        val apiKey = token?.apiKey.orEmpty()
        val homeAttr = PublicBaseUrlResolver.escapeHtml(home)
        val keyAttr = PublicBaseUrlResolver.escapeHtml(apiKey)

        val remoteHint =
            if (remoteAccess) {
                """
                <p class="warn">You are reaching this adapter through a public URL. Use <strong>device code</strong> login —
                browser OAuth always returns to <code>http://localhost:1455</code> on the machine running OpenGPT, not on your phone/laptop.</p>
                """.trimIndent()
            } else {
                ""
            }

        val body =
            if (authenticated) {
                """
                <p class="ok">Authenticated with ChatGPT.</p>
                <div class="card">
                  <label>Base URL</label>
                  <div class="row">
                    <input id="baseUrl" readonly value="$homeAttr/v1"/>
                    <button type="button" onclick="copyField('baseUrl')">Copy</button>
                  </div>
                  <label>API Key</label>
                  <div class="row">
                    <input id="apiKey" readonly value="$keyAttr"/>
                    <button type="button" onclick="copyField('apiKey')">Copy</button>
                  </div>
                  <p class="hint">Paste both values into Cursor → Settings → Models → OpenAI API Key + Override OpenAI Base URL.</p>
                  <p class="warn">Cursor often blocks <code>localhost</code> ("Access to private networks is forbidden"). If that happens, expose this adapter with a public HTTPS tunnel (e.g. Cloudflare Tunnel / ngrok) and use that URL as the base URL instead.</p>
                  <p class="hint">Clients on other machines only need this Base URL + API key. They do not run OpenGPT themselves.</p>
                </div>
                <form method="post" action="/auth/regenerate-key">
                  <button type="submit" class="secondary">Regenerate API key</button>
                </form>
                """.trimIndent()
            } else {
                """
                <p>Not authenticated.</p>
                $remoteHint
                <p class="hint">Browser login returns to <code>http://localhost:1455/auth/callback</code> (Codex-registered redirect) and only works in a browser on the OpenGPT host.</p>
                <p class="hint">Device code login works from any device. Enable <strong>Device code authorization</strong> in ChatGPT Security settings first.</p>
                """.trimIndent()
            }

        val browserLabel = if (authenticated) "Re-login with browser" else "Login with browser"
        val deviceLabel = if (authenticated) "Re-login with device code" else "Login with device code"
        val actions =
            if (remoteAccess) {
                """
              <div class="actions">
                <button type="button" class="button" id="deviceLoginBtn" onclick="startDeviceLogin()">$deviceLabel</button>
                <a class="secondary" href="/auth/login">$browserLabel</a>
              </div>
                """.trimIndent()
            } else {
                """
              <div class="actions">
                <a class="button" href="/auth/login">$browserLabel</a>
                <button type="button" class="secondary" id="deviceLoginBtn" onclick="startDeviceLogin()">$deviceLabel</button>
              </div>
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
                a.button, button.button, button { display: inline-block; background: #111; color: #fff; padding: .65rem 1.1rem; border-radius: .5rem; text-decoration: none; border: 0; cursor: pointer; font: inherit; }
                a.secondary, button.secondary, .secondary { background: #444; color: #fff; padding: .65rem 1.1rem; border-radius: .5rem; text-decoration: none; border: 0; cursor: pointer; font: inherit; margin-top: 0; }
                button.linkish { background: transparent; color: #111; padding: 0; text-decoration: underline; margin-top: .75rem; }
                .actions { display: flex; flex-wrap: wrap; gap: .75rem; margin: 1rem 0; align-items: center; }
                .ok { color: #0a7a32; }
                .err { color: #a11; }
                .hint, .warn { color: #555; font-size: .95rem; }
                .warn { color: #8a5a00; }
                .card { background: #f6f6f6; padding: 1rem; border-radius: .75rem; margin: 1rem 0; }
                label { display: block; font-weight: 600; margin: .75rem 0 .35rem; }
                .row { display: flex; gap: .5rem; }
                input { flex: 1; font-family: ui-monospace, monospace; padding: .55rem .65rem; border: 1px solid #ccc; border-radius: .4rem; }
                code { background: #eee; padding: .1rem .35rem; border-radius: .25rem; }
                #toast { display:none; margin-top:.75rem; color:#0a7a32; font-weight:600; }
                #devicePanel { display:none; }
                .code { font-size: 1.6rem; letter-spacing: .12em; font-family: ui-monospace, monospace; font-weight: 700; }
              </style>
            </head>
            <body>
              <h1>OpenGPT</h1>
              $body
              $actions
              <div id="devicePanel" class="card">
                <p>Open this link and enter the code:</p>
                <p><a id="deviceUrl" href="#" target="_blank" rel="noopener"></a></p>
                <p class="code" id="deviceCode"></p>
                <p class="hint" id="deviceStatus">Waiting for approval…</p>
                <button type="button" class="linkish" onclick="cancelDeviceLogin()">Cancel</button>
              </div>
              <div id="toast">Copied</div>
              <script>
                let deviceTimer = null;
                let deviceIntervalMs = 5000;

                function copyField(id) {
                  const el = document.getElementById(id);
                  navigator.clipboard.writeText(el.value).then(() => {
                    const toast = document.getElementById('toast');
                    toast.style.display = 'block';
                    setTimeout(() => toast.style.display = 'none', 1200);
                  });
                }

                function setDeviceStatus(text, isError) {
                  const el = document.getElementById('deviceStatus');
                  el.textContent = text;
                  el.className = isError ? 'err' : 'hint';
                }

                function cancelDeviceLogin() {
                  if (deviceTimer) {
                    clearTimeout(deviceTimer);
                    deviceTimer = null;
                  }
                  document.getElementById('devicePanel').style.display = 'none';
                  setDeviceStatus('Waiting for approval…', false);
                }

                async function pollDeviceLogin() {
                  try {
                    const res = await fetch('/auth/device/poll', { method: 'POST' });
                    const data = await res.json();
                    if (data.status === 'completed') {
                      setDeviceStatus('Authenticated. Reloading…', false);
                      window.location.reload();
                      return;
                    }
                    if (data.status === 'pending') {
                      setDeviceStatus('Waiting for approval…', false);
                      deviceTimer = setTimeout(pollDeviceLogin, deviceIntervalMs);
                      return;
                    }
                    if (data.status === 'expired') {
                      setDeviceStatus('Code expired. Start device login again.', true);
                      return;
                    }
                    if (data.status === 'no_pending') {
                      setDeviceStatus('No active device login. Start again.', true);
                      return;
                    }
                    setDeviceStatus(data.message || 'Device login failed.', true);
                  } catch (err) {
                    setDeviceStatus('Device login poll failed.', true);
                  }
                }

                async function startDeviceLogin() {
                  cancelDeviceLogin();
                  setDeviceStatus('Requesting device code…', false);
                  document.getElementById('devicePanel').style.display = 'block';
                  try {
                    const res = await fetch('/auth/device/start', { method: 'POST' });
                    const data = await res.json().catch(() => ({}));
                    if (!res.ok) {
                      const msg = data.message || data.detail || data.error || ('HTTP ' + res.status);
                      setDeviceStatus(typeof msg === 'string' ? msg : 'Could not start device login.', true);
                      return;
                    }
                    document.getElementById('deviceUrl').href = data.verificationUrl;
                    document.getElementById('deviceUrl').textContent = data.verificationUrl;
                    document.getElementById('deviceCode').textContent = data.userCode;
                    deviceIntervalMs = Math.max(2000, (data.intervalSeconds || 5) * 1000);
                    setDeviceStatus('Waiting for approval…', false);
                    deviceTimer = setTimeout(pollDeviceLogin, deviceIntervalMs);
                  } catch (err) {
                    setDeviceStatus('Could not start device login.', true);
                  }
                }
              </script>
            </body>
            </html>
            """.trimIndent()
    }

    private fun successHtml(apiKey: String, home: String): String {
        val homeAttr = PublicBaseUrlResolver.escapeHtml(home)
        val keyAttr = PublicBaseUrlResolver.escapeHtml(apiKey)
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"/>
              <meta http-equiv="refresh" content="1;url=$homeAttr/"/>
              <title>Authentication successful</title>
            </head>
            <body style="font-family:system-ui;max-width:44rem;margin:4rem auto;padding:0 1rem">
              <h1>Authentication successful</h1>
              <p>Use these values in Cursor:</p>
              <p>Base URL: <code>$homeAttr/v1</code></p>
              <p>API Key: <code>$keyAttr</code></p>
              <p><a href="$homeAttr/">Open adapter home</a> to copy them with one click.</p>
            </body>
            </html>
            """.trimIndent()
    }
}
