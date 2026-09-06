package com.opengpt.codex

import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexRequestMapperTest {
    private val mapper = CodexRequestMapper(ObjectMapper(), ReasoningStateCache(), CursorImageRecovery())

    @Test
    fun `maps chat image_url parts to responses input_image`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.6-sol",
                  "messages":[
                    {
                      "role":"user",
                      "content":[
                        {"type":"text","text":"What is in this image?"},
                        {
                          "type":"image_url",
                          "image_url":{
                            "url":"data:image/jpeg;base64,/9j/4AAQ"
                          }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        val content = responses.path("input")[0].path("content")
        assertEquals(2, content.size())
        assertEquals("input_text", content[0].path("type").asText())
        assertEquals("What is in this image?", content[0].path("text").asText())
        assertEquals("input_image", content[1].path("type").asText())
        assertEquals("data:image/jpeg;base64,/9j/4AAQ", content[1].path("image_url").asText())
        assertEquals("auto", content[1].path("detail").asText())
    }

    @Test
    fun `maps messages to input and system to instructions`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.4",
                  "messages":[
                    {"role":"system","content":"You are helpful"},
                    {"role":"user","content":"Explain this code"}
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        assertEquals("You are helpful", responses.path("instructions").asText())
        assertEquals("user", responses.path("input")[0].path("role").asText())
        assertEquals(false, responses.path("store").asBoolean())
    }

    @Test
    fun `maps reasoning_effort to responses reasoning`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.6-sol",
                  "messages":[{"role":"user","content":"hi"}],
                  "reasoning_effort":"xhigh"
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        assertEquals("xhigh", responses.path("reasoning").path("effort").asText())
        assertEquals("gpt-5.6-sol", responses.path("model").asText())
    }

    @Test
    fun `maps Cursor thinking_effort model parameter`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.6-sol",
                  "messages":[{"role":"user","content":"hi"}],
                  "cursor_model_params":[
                    {"id":"thinking_effort","value":"medium"}
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        assertEquals("medium", responses.path("reasoning").path("effort").asText())
    }

    @Test
    fun `maps assistant tool_calls and tool results to responses items`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.6-sol",
                  "messages":[
                    {"role":"user","content":"read it"},
                    {
                      "role":"assistant",
                      "content":null,
                      "tool_calls":[
                        {
                          "id":"call_1",
                          "type":"function",
                          "function":{"name":"ReadFile","arguments":"{\"path\":\"/tmp/a.py\"}"}
                        }
                      ]
                    },
                    {"role":"tool","tool_call_id":"call_1","content":"print('hi')"}
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        assertEquals("function_call", responses.path("input")[1].path("type").asText())
        assertEquals("call_1", responses.path("input")[1].path("call_id").asText())
        assertEquals("ReadFile", responses.path("input")[1].path("name").asText())
        assertEquals("{\"path\":\"/tmp/a.py\"}", responses.path("input")[1].path("arguments").asText())
        assertEquals("function_call_output", responses.path("input")[2].path("type").asText())
        assertEquals("call_1", responses.path("input")[2].path("call_id").asText())
        assertEquals("print('hi')", responses.path("input")[2].path("output").asText())
    }

    @Test
    fun `maps custom ApplyPatch tool_calls and results`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.6-sol",
                  "messages":[
                    {"role":"user","content":"edit it"},
                    {
                      "role":"assistant",
                      "content":null,
                      "tool_calls":[
                        {
                          "id":"call_patch_1",
                          "type":"custom",
                          "custom":{"name":"ApplyPatch","input":"*** Begin Patch\\n*** End Patch\\n"}
                        }
                      ]
                    },
                    {"role":"tool","tool_call_id":"call_patch_1","content":"Success"}
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        assertEquals("custom_tool_call", responses.path("input")[1].path("type").asText())
        assertEquals("call_patch_1", responses.path("input")[1].path("call_id").asText())
        assertEquals("ApplyPatch", responses.path("input")[1].path("name").asText())
        assertEquals("*** Begin Patch\\n*** End Patch\\n", responses.path("input")[1].path("input").asText())
        assertEquals("custom_tool_call_output", responses.path("input")[2].path("type").asText())
        assertEquals("call_patch_1", responses.path("input")[2].path("call_id").asText())
        assertEquals("Success", responses.path("input")[2].path("output").asText())
    }

    @Test
    fun `promotes function-shaped ApplyPatch history back to custom_tool_call`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.6-sol",
                  "tools":[{"type":"custom","name":"ApplyPatch","description":"edit"}],
                  "messages":[
                    {"role":"user","content":"edit it"},
                    {
                      "role":"assistant",
                      "content":null,
                      "tool_calls":[
                        {
                          "id":"call_patch_1",
                          "type":"function",
                          "function":{
                            "name":"ApplyPatch",
                            "arguments":"*** Begin Patch\n*** Add File: /tmp/a.py\n+x\n*** End Patch\n"
                          }
                        }
                      ]
                    },
                    {"role":"tool","tool_call_id":"call_patch_1","content":"Success"}
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        assertEquals("custom_tool_call", responses.path("input")[1].path("type").asText())
        assertEquals("ApplyPatch", responses.path("input")[1].path("name").asText())
        assertEquals("*** Begin Patch\n*** Add File: /tmp/a.py\n+x\n*** End Patch\n", responses.path("input")[1].path("input").asText())
        assertEquals("custom_tool_call_output", responses.path("input")[2].path("type").asText())
    }

    @Test
    fun `shortens oversized tool call ids for Codex`() {
        val longId = "call_" + "a".repeat(90)
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.6-sol",
                  "messages":[
                    {"role":"user","content":"read it"},
                    {
                      "role":"assistant",
                      "content":null,
                      "tool_calls":[
                        {
                          "id":"$longId",
                          "type":"function",
                          "function":{"name":"ReadFile","arguments":"{\"path\":\"/tmp/a.py\"}"}
                        }
                      ]
                    },
                    {"role":"tool","tool_call_id":"$longId","content":"print('hi')"}
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        val callId = responses.path("input")[1].path("call_id").asText()
        val resultId = responses.path("input")[2].path("call_id").asText()
        assertTrue(callId.length <= 64)
        assertEquals(callId, resultId)
        assertTrue(callId.startsWith("call_"))
    }

    @Test
    fun `normalizes chat tools to responses tools`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.4",
                  "messages":[{"role":"user","content":"hi"}],
                  "tools":[
                    {
                      "type":"function",
                      "function":{
                        "name":"read_file",
                        "description":"Read a file",
                        "parameters":{"type":"object"}
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        assertEquals("read_file", responses.path("tools")[0].path("name").asText())
        assertEquals(false, responses.path("tools")[0].path("strict").asBoolean())
        assertEquals(true, responses.path("parallel_tool_calls").asBoolean())
    }

    @Test
    fun `forwards explicit parallel_tool_calls false`() {
        val request =
            ObjectMapper().readTree(
                """
                {
                  "model":"gpt-5.4",
                  "messages":[{"role":"user","content":"hi"}],
                  "parallel_tool_calls":false,
                  "tools":[
                    {
                      "type":"function",
                      "function":{"name":"read_file","parameters":{"type":"object"}}
                    }
                  ]
                }
                """.trimIndent(),
            )
        val responses = mapper.toResponsesRequest(request)
        assertEquals(false, responses.path("parallel_tool_calls").asBoolean())
    }
}
