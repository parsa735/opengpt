package com.opengpt.streaming

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class StreamTranslatorTest {
    private val translator = StreamTranslator(ObjectMapper())
    private val mapper = ObjectMapper()

    @Test
    fun `converts text delta with role bootstrap`() {
        val session = translator.newSession("gpt-5.4")
        val event = mapper.readTree("""{"type":"response.output_text.delta","delta":"hello"}""")
        val chunks = session.toChatCompletionChunk(event)
        assertEquals(2, chunks.size)
        assertEquals("assistant", chunks[0].path("choices")[0].path("delta").path("role").asText())
        assertEquals("hello", chunks[1].path("choices")[0].path("delta").path("content").asText())
        assertTrue(session.hadContent())
    }

    @Test
    fun `buffers function tool call until arguments are complete`() {
        val session = translator.newSession("gpt-5.6-sol")
        val started =
            session.toChatCompletionChunk(
                mapper.readTree(
                    """
                    {
                      "type":"response.output_item.added",
                      "output_index":3,
                      "item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"ReadFile","arguments":""}
                    }
                    """.trimIndent(),
                ),
            )
        assertTrue(started.none { it.path("choices")[0].path("delta").has("tool_calls") })

        assertTrue(
            session.toChatCompletionChunk(
                mapper.readTree(
                    """
                    {
                      "type":"response.function_call_arguments.delta",
                      "item_id":"item_1",
                      "output_index":3,
                      "delta":"{\"path\":\"/tmp/a.py\"}"
                    }
                    """.trimIndent(),
                ),
            ).isEmpty(),
        )

        val flushed =
            session.toChatCompletionChunk(
                mapper.readTree(
                    """{"type":"response.function_call_arguments.done","item_id":"item_1","arguments":"{\"path\":\"/tmp/a.py\"}"}""",
                ),
            ).single()
        val toolCall = flushed.path("choices")[0].path("delta").path("tool_calls")[0]
        assertEquals(0, toolCall.path("index").asInt())
        assertEquals("call_1", toolCall.path("id").asText())
        assertEquals("ReadFile", toolCall.path("function").path("name").asText())
        assertEquals("{\"path\":\"/tmp/a.py\"}", toolCall.path("function").path("arguments").asText())

        val finished =
            session.toChatCompletionChunk(
                mapper.readTree("""{"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":1}}}"""),
            )
        assertEquals("tool_calls", finished[0].path("choices")[0].path("finish_reason").asText())
    }

    @Test
    fun `suppresses empty function tool arguments and defers finish for retry`() {
        val session = translator.newSession("gpt-5.6-sol")
        session.toChatCompletionChunk(
            mapper.readTree(
                """
                {
                  "type":"response.output_item.added",
                  "item":{"type":"function_call","id":"item_1","call_id":"call_empty","name":"StrReplace","arguments":""}
                }
                """.trimIndent(),
            ),
        )
        assertTrue(
            session.toChatCompletionChunk(
                mapper.readTree(
                    """{"type":"response.function_call_arguments.delta","item_id":"item_1","delta":"{}"}""",
                ),
            ).isEmpty(),
        )
        assertTrue(
            session.toChatCompletionChunk(
                mapper.readTree(
                    """{"type":"response.function_call_arguments.done","item_id":"item_1","arguments":"{}"}""",
                ),
            ).isEmpty(),
        )

        val finished =
            session.toChatCompletionChunk(
                mapper.readTree("""{"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":1}}}"""),
            )
        assertTrue(finished.isEmpty())
        assertTrue(session.needsEmptyToolRetry())
        assertEquals(1, session.suppressedEmptyTools().size)
        assertEquals("StrReplace", session.suppressedEmptyTools()[0].name)
        assertEquals("{}", session.suppressedEmptyTools()[0].arguments)
        assertFalse(session.hadContent())
        assertEquals(emptyList<String>(), session.toolCallIds())
    }

    @Test
    fun `converts completed event with openai usage chunk`() {
        val session = translator.newSession("gpt-5.6-sol")
        val event =
            mapper.readTree(
                """
                {
                  "type":"response.completed",
                  "response":{
                    "usage":{
                      "input_tokens":100,
                      "output_tokens":50,
                      "total_tokens":150,
                      "output_tokens_details":{"reasoning_tokens":20}
                    }
                  }
                }
                """.trimIndent(),
            )
        val chunks = session.toChatCompletionChunk(event)
        assertEquals(2, chunks.size)
        assertEquals("stop", chunks[0].path("choices")[0].path("finish_reason").asText())
        val usage = chunks[1].path("usage")
        assertEquals(100, usage.path("prompt_tokens").asInt())
        assertEquals(50, usage.path("completion_tokens").asInt())
        assertEquals(150, usage.path("total_tokens").asInt())
        assertEquals(20, usage.path("completion_tokens_details").path("reasoning_tokens").asInt())
        assertTrue(chunks[1].path("choices").isEmpty)
    }

    @Test
    fun `buffers custom_tool_call ApplyPatch into one tool_calls chunk`() {
        val session = translator.newSession("gpt-5.6-sol")
        val started =
            session.toChatCompletionChunk(
                mapper.readTree(
                    """
                    {
                      "type":"response.output_item.added",
                      "output_index":5,
                      "item":{
                        "type":"custom_tool_call",
                        "id":"ctc_1",
                        "call_id":"call_patch_1",
                        "name":"ApplyPatch",
                        "input":""
                      }
                    }
                    """.trimIndent(),
                ),
            )
        assertTrue(started.isEmpty() || started.none { it.path("choices")[0].path("delta").has("tool_calls") })

        val patch = "*** Begin Patch\n*** End Patch\n"
        assertTrue(
            session.toChatCompletionChunk(
                mapper.readTree(
                    """
                    {
                      "type":"response.custom_tool_call_input.delta",
                      "item_id":"ctc_1",
                      "output_index":5,
                      "delta":"*** Begin Patch\n"
                    }
                    """.trimIndent(),
                ),
            ).isEmpty(),
        )
        assertTrue(
            session.toChatCompletionChunk(
                mapper.readTree(
                    """
                    {
                      "type":"response.custom_tool_call_input.delta",
                      "item_id":"ctc_1",
                      "delta":"*** End Patch\n"
                    }
                    """.trimIndent(),
                ),
            ).isEmpty(),
        )

        val done =
            mapper.createObjectNode().apply {
                put("type", "response.output_item.done")
                set(
                    "item",
                    mapper.createObjectNode().apply {
                        put("type", "custom_tool_call")
                        put("id", "ctc_1")
                        put("call_id", "call_patch_1")
                        put("name", "ApplyPatch")
                        put("input", patch)
                    },
                )
            }
        val flushed = session.toChatCompletionChunk(done).single()
        val toolCall = flushed.path("choices")[0].path("delta").path("tool_calls")[0]
        assertEquals(0, toolCall.path("index").asInt())
        assertEquals("function", toolCall.path("type").asText())
        assertEquals("call_patch_1", toolCall.path("id").asText())
        assertEquals("ApplyPatch", toolCall.path("function").path("name").asText())
        assertEquals(patch, toolCall.path("function").path("arguments").asText())

        val finished =
            session.toChatCompletionChunk(
                mapper.readTree("""{"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":1}}}"""),
            )
        assertEquals("tool_calls", finished[0].path("choices")[0].path("finish_reason").asText())
        assertEquals(listOf("call_patch_1"), session.toolCallIds())
    }

    @Test
    fun `handles function_call_arguments_done without item`() {
        val session = translator.newSession("gpt-5.6-sol")
        session.toChatCompletionChunk(
            mapper.readTree(
                """
                {
                  "type":"response.output_item.added",
                  "item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"ReadFile","arguments":""}
                }
                """.trimIndent(),
            ),
        )
        val chunks =
            session.toChatCompletionChunk(
                mapper.readTree(
                    """{"type":"response.function_call_arguments.done","item_id":"item_1","arguments":"{\"path\":\"/tmp/a.py\"}"}""",
                ),
            )
        assertEquals(1, chunks.size)
        assertEquals(
            "{\"path\":\"/tmp/a.py\"}",
            chunks[0].path("choices")[0].path("delta").path("tool_calls")[0].path("function").path("arguments").asText(),
        )
        val finished =
            session.toChatCompletionChunk(
                mapper.readTree("""{"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":1}}}"""),
            )
        assertEquals("tool_calls", finished[0].path("choices")[0].path("finish_reason").asText(""))
    }

    @Test
    fun `captures reasoning items from output_item_done`() {
        val session = translator.newSession("gpt-5.6-sol")
        session.toChatCompletionChunk(
            mapper.readTree(
                """
                {
                  "type":"response.output_item.done",
                  "item":{
                    "type":"reasoning",
                    "id":"rs_1",
                    "encrypted_content":"enc-data",
                    "summary":[{"type":"summary_text","text":"plan"}]
                  }
                }
                """.trimIndent(),
            ),
        )
        session.toChatCompletionChunk(
            mapper.readTree(
                """
                {
                  "type":"response.output_item.added",
                  "item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"ReadFile","arguments":""}
                }
                """.trimIndent(),
            ),
        )
        session.toChatCompletionChunk(
            mapper.readTree(
                """{"type":"response.function_call_arguments.done","item_id":"item_1","arguments":"{\"path\":\"/tmp/a.py\"}"}""",
            ),
        )
        assertEquals(1, session.reasoningItems().size)
        assertEquals("enc-data", session.reasoningItems()[0].path("encrypted_content").asString(""))
        assertEquals(listOf("call_1"), session.toolCallIds())
    }

    @Test
    fun `ignores unknown events`() {
        val session = translator.newSession("gpt-5.4")
        val event = mapper.readTree("""{"type":"response.reasoning_summary_text.delta"}""")
        assertTrue(session.toChatCompletionChunk(event).isEmpty())
    }
}
