package com.opengpt.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallIdNormalizerTest {
    @Test
    fun `keeps short ids`() {
        assertEquals("call_abc123", CallIdNormalizer.normalize("call_abc123"))
    }

    @Test
    fun `hashes long ids under 64 chars deterministically`() {
        val longId = "call_" + "x".repeat(80)
        val first = CallIdNormalizer.normalize(longId)
        val second = CallIdNormalizer.normalize(longId)
        assertTrue(first.length <= 64)
        assertEquals(first, second)
    }
}
