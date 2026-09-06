package com.opengpt.auth

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PkceCodes(
    val verifier: String,
    val challenge: String,
)

@Component
class PkceGenerator {
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun generate(): PkceCodes {
        val verifier =
            buildString(43) {
                repeat(43) {
                    append(alphabet[random.nextInt(alphabet.length)])
                }
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PkceCodes(verifier = verifier, challenge = challenge)
    }

    fun randomState(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
