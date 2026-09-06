package com.opengpt.model

data class OAuthToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val accountId: String?,
    val residency: String? = null,
    /** Local adapter API key for Cursor / OpenAI-compatible clients. */
    val apiKey: String? = null,
)
