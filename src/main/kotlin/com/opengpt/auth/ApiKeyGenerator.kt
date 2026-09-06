package com.opengpt.auth

import java.security.SecureRandom
import java.util.Base64

object ApiKeyGenerator {
    private val random = SecureRandom()

    /** Generates a local adapter key shaped like OpenAI keys for client compatibility. */
    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "sk-cla-$suffix"
    }
}
