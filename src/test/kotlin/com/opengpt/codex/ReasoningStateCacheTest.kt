package com.opengpt.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class ReasoningStateCacheTest {
    private val mapper = ObjectMapper()
    private val cache = ReasoningStateCache()

    @Test
    fun `stores and returns encrypted reasoning by call id`() {
        val item = mapper.createObjectNode()
        item.put("type", "reasoning")
        item.put("id", "rs_1")
        item.put("encrypted_content", "abc")
        item.putArray("summary")

        cache.rememberTurn(listOf("call_1", "call_2"), null, listOf(item))
        val restored = cache.takeForCallIds(listOf("call_2"))
        assertEquals(1, restored.size)
        assertEquals("rs_1", restored[0].path("id").asString(""))
        assertEquals("abc", restored[0].path("encrypted_content").asString(""))
    }

    @Test
    fun `stores reasoning by assistant text for text-only turns`() {
        val item = mapper.createObjectNode()
        item.put("type", "reasoning")
        item.put("id", "rs_2")
        item.put("encrypted_content", "xyz")
        cache.rememberTurn(emptyList(), "I will continue the script.", listOf(item))
        val restored = cache.takeForAssistantText("I will continue the script.")
        assertEquals(1, restored.size)
        assertEquals("xyz", restored[0].path("encrypted_content").asString(""))
    }

    @Test
    fun `ignores reasoning without encrypted content`() {
        val item = mapper.createObjectNode()
        item.put("type", "reasoning")
        item.put("id", "rs_1")
        cache.rememberTurn(listOf("call_1"), "hello", listOf(item))
        assertTrue(cache.takeForCallIds(listOf("call_1")).isEmpty())
    }
}
