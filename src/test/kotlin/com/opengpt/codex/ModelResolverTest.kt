package com.opengpt.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class ModelResolverTest {
    private val resolver = ModelResolver()

    @Test
    fun `passes gpt-5_6-sol through unchanged`() {
        val resolved = resolver.resolve("gpt-5.6-sol")
        assertEquals("gpt-5.6-sol", resolved.model)
        assertNull(resolved.reasoningEffort)
    }

    @Test
    fun `passes terra and luna through`() {
        assertEquals("gpt-5.6-terra", resolver.resolve("gpt-5.6-terra").model)
        assertEquals("gpt-5.6-luna", resolver.resolve("gpt-5.6-luna").model)
    }

    @Test
    fun `keeps known codex models`() {
        assertEquals("gpt-5.4", resolver.resolve("gpt-5.4").model)
        assertEquals("gpt-5.5", resolver.resolve("gpt-5.5").model)
    }

    @Test
    fun `strips effort suffix into reasoningEffort`() {
        val resolved = resolver.resolve("gpt-5.6-sol-xhigh")
        assertEquals("gpt-5.6-sol", resolved.model)
        assertEquals("xhigh", resolved.reasoningEffort)
    }

    @Test
    fun `strips effort suffix before generic model compatibility`() {
        val terra = resolver.resolve("gpt-5.6-terra-medium")
        assertEquals("gpt-5.6-terra", terra.model)
        assertEquals("medium", terra.reasoningEffort)

        val known = resolver.resolve("gpt-5.5-high")
        assertEquals("gpt-5.5", known.model)
        assertEquals("high", known.reasoningEffort)
    }

    @Test
    fun `resolves Cursor-safe aliases to Codex models and efforts`() {
        val sol = resolver.resolve("cla-sol-low")
        assertEquals("gpt-5.6-sol", sol.model)
        assertEquals("low", sol.reasoningEffort)

        val terra = resolver.resolve("cla-terra-high")
        assertEquals("gpt-5.6-terra", terra.model)
        assertEquals("high", terra.reasoningEffort)

        val luna = resolver.resolve("CLA-LUNA-XHIGH")
        assertEquals("gpt-5.6-luna", luna.model)
        assertEquals("xhigh", luna.reasoningEffort)
    }

    @Test
    fun `extracts Cursor model parameter effort`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.6-sol",
                  "reasoning_effort":"high",
                  "cursor_model_params":[{"id":"thinking_effort","value":"medium"}]
                }
                """.trimIndent(),
            )
        assertEquals("medium", ReasoningEffortResolver.fromRequest(request))
    }

    @Test
    fun `prefers Responses reasoning object and normalizes max`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "reasoning":{"effort":"max"},
                  "cursor_model_params":[{"id":"thinking_effort","value":"medium"}]
                }
                """.trimIndent(),
            )
        assertEquals("xhigh", ReasoningEffortResolver.fromRequest(request))
    }

    @Test
    fun `advertises effort-specific Cursor workaround models`() {
        assertTrue(ModelResolver.PUBLIC_MODELS.contains("gpt-5.6-sol-medium"))
        assertTrue(ModelResolver.PUBLIC_MODELS.contains("gpt-5.6-terra-high"))
        assertTrue(ModelResolver.PUBLIC_MODELS.contains("gpt-5.6-luna-low"))
        assertTrue(ModelResolver.PUBLIC_MODELS.contains("cla-sol-low"))
        assertTrue(ModelResolver.PUBLIC_MODELS.contains("cla-terra-medium"))
        assertTrue(ModelResolver.PUBLIC_MODELS.contains("cla-luna-xhigh"))
    }

    @Test
    fun `defaults gpt-5_6 models to xhigh`() {
        assertEquals("xhigh", ModelResolver.defaultEffortFor("gpt-5.6-sol"))
        assertEquals("high", ModelResolver.defaultEffortFor("gpt-5.5"))
    }

    @Test
    fun `marks gpt-5_6-sol as codex compatible`() {
        assertTrue(resolver.isCodexCompatible("gpt-5.6-sol"))
    }
}
