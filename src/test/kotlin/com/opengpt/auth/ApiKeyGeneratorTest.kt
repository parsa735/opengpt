package com.opengpt.auth

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiKeyGeneratorTest {
    @Test
    fun `generates sk-cla prefixed key`() {
        val key = ApiKeyGenerator.generate()
        assertTrue(key.startsWith("sk-cla-"))
        assertTrue(key.length > 20)
    }
}
