package com.opengpt.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "adapter")
data class AdapterProperties(
    val storagePath: String,
    /** UI / client-facing base URL for the main API (may differ from OAuth callback port). */
    val publicBaseUrl: String = "http://localhost:5080",
    /** Plain Cursor↔Codex traffic dump for lab debugging. */
    val trafficLogPath: String = System.getProperty("user.home") + "/.opengpt/traffic.log",
)

@ConfigurationProperties(prefix = "openai.oauth")
data class OAuthProperties(
    val clientId: String,
    val issuer: String,
    /**
     * Must match a redirect_uri registered for the Codex OAuth client.
     * OpenCode/Codex CLI use http://localhost:1455/auth/callback
     */
    val callbackUrl: String = "http://localhost:1455/auth/callback",
    val callbackPort: Int = 1455,
    val scope: String = "openid profile email offline_access",
    /** Authorize URL originator; Codex client expects values like "opencode". */
    val originator: String = "opencode",
)

@ConfigurationProperties(prefix = "codex")
data class CodexProperties(
    val endpoint: String,
    val userAgent: String = "opengpt",
    val originator: String = "opengpt",
)
