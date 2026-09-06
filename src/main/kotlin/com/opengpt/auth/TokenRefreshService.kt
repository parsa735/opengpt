package com.opengpt.auth

import com.opengpt.model.OAuthToken
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

class AuthenticationRequiredException(
    message: String = "ChatGPT account authentication required",
) : RuntimeException(message)

@Service
class TokenRefreshService(
    private val tokenStore: TokenStore,
    private val oauthService: OAuthService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val refreshInFlight = AtomicReference<CompletableFuture<OAuthToken>?>(null)

    open fun getValidToken(): OAuthToken {
        val current = tokenStore.getToken() ?: throw AuthenticationRequiredException()
        if (current.expiresAt > System.currentTimeMillis() + 30_000) {
            return current
        }
        return refresh(current)
    }

    private fun refresh(current: OAuthToken): OAuthToken {
        val existing = refreshInFlight.get()
        if (existing != null) return existing.join()

        val future =
            CompletableFuture.supplyAsync {
                try {
                    oauthService.refresh(current.refreshToken)
                } catch (error: Exception) {
                    log.warn("Token refresh failed")
                    throw AuthenticationRequiredException("ChatGPT account authentication required")
                }
            }
        if (!refreshInFlight.compareAndSet(null, future)) {
            return refreshInFlight.get()!!.join()
        }
        return try {
            future.join()
        } finally {
            refreshInFlight.compareAndSet(future, null)
        }
    }
}
