package com.opengpt.codex

import com.opengpt.util.JsonNodes
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

@Component
class CodexRequestMapper(
    private val objectMapper: ObjectMapper,
    private val reasoningStateCache: ReasoningStateCache,
    private val cursorImageRecovery: CursorImageRecovery,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Converts a Chat Completions style body into an OpenAI Responses body.
     * Matches OpenCode / OpenAI Responses lowering for tools and tool history.
     */
    fun toResponsesRequest(chatRequest: JsonNode): ObjectNode {
        val out = objectMapper.createObjectNode()
        out.put("model", JsonNodes.textAt(chatRequest, "model", "gpt-5.4"))
        out.put("stream", chatRequest.path("stream").asBoolean(true))

        val instructions = StringBuilder()
        val input = objectMapper.createArrayNode()
        val customCallIds = mutableSetOf<String>()
        val customToolNames = customToolNames(chatRequest.path("tools"))
        val messages = chatRequest.path("messages")
        if (messages.isArray) {
            for (message in messages) {
                when (JsonNodes.textAt(message, "role")) {
                    "system", "developer" -> {
                        val content = flattenContent(message.path("content"))
                        if (content.isNotEmpty()) {
                            if (instructions.isNotEmpty()) instructions.append('\n')
                            instructions.append(content)
                        }
                    }
                    "assistant" -> appendAssistant(message, input, customCallIds, customToolNames)
                    "tool" -> appendToolResult(message, input, customCallIds)
                    else -> appendUser(message, input)
                }
            }
        }

        if (instructions.isNotEmpty()) {
            out.put("instructions", instructions.toString())
        }
        out.set("input", input)

        if (chatRequest.has("tools")) {
            out.set("tools", normalizeTools(chatRequest.path("tools")))
        }
        if (chatRequest.has("tool_choice")) {
            out.set("tool_choice", normalizeToolChoice(chatRequest.path("tool_choice")))
        }
        applyParallelToolCalls(chatRequest, out)
        if (chatRequest.has("temperature") && !chatRequest.path("temperature").isNull) {
            out.set("temperature", chatRequest.path("temperature"))
        }
        if (chatRequest.has("top_p") && !chatRequest.path("top_p").isNull) {
            out.set("top_p", chatRequest.path("top_p"))
        }

        applyReasoning(chatRequest, out)

        // Codex / OpenCode defaults for subscription path.
        out.put("store", false)
        return out
    }

    /**
     * Forward Cursor's `parallel_tool_calls` into Responses. When tools are present and
     * the client omits the field, default to true so Codex may emit multiple edits per turn.
     */
    private fun applyParallelToolCalls(
        chatRequest: JsonNode,
        out: ObjectNode,
    ) {
        val tools = chatRequest.path("tools")
        val hasTools = tools.isArray && tools.size() > 0
        if (chatRequest.has("parallel_tool_calls") && !chatRequest.path("parallel_tool_calls").isNull) {
            out.set("parallel_tool_calls", chatRequest.path("parallel_tool_calls"))
            return
        }
        if (hasTools) {
            out.put("parallel_tool_calls", true)
        }
    }

    private fun appendUser(
        message: JsonNode,
        input: ArrayNode,
    ) {
        val content = message.path("content")
        val diskPaths = cursorImageRecovery.extractDiskPaths(content)
        val contentArray = toInputContent(content, diskPaths)
        if (contentArray.isEmpty) return
        val item = objectMapper.createObjectNode()
        item.put("role", "user")
        item.set("content", contentArray)
        input.add(item)
    }

    /**
     * Chat Completions multimodal parts → Responses input parts.
     * Cursor sends `image_url` with data-URLs; Codex expects `input_image`.
     */
    private fun toInputContent(
        content: JsonNode,
        diskPaths: List<String> = emptyList(),
    ): ArrayNode {
        val out = objectMapper.createArrayNode()
        if (content.isTextual) {
            val text = JsonNodes.text(content)
            if (text.isNotEmpty()) {
                val part = objectMapper.createObjectNode()
                part.put("type", "input_text")
                part.put("text", text)
                out.add(part)
            }
            return out
        }
        if (!content.isArray) {
            if (!content.isMissingNode && !content.isNull) {
                val part = objectMapper.createObjectNode()
                part.put("type", "input_text")
                part.put("text", content.toString())
                out.add(part)
            }
            return out
        }
        var imageIndex = 0
        for (part in content) {
            if (part.isTextual) {
                val text = JsonNodes.text(part)
                if (text.isEmpty()) continue
                val mapped = objectMapper.createObjectNode()
                mapped.put("type", "input_text")
                mapped.put("text", text)
                out.add(mapped)
                continue
            }
            val type = JsonNodes.textAt(part, "type")
            if (type == "image_url" || type == "input_image" || part.has("image_url")) {
                val image = mapImagePart(part, diskPaths, imageIndex)
                imageIndex += 1
                if (image != null) {
                    out.add(image)
                    log.info(
                        "Mapped image part mime={} bytes≈{} source={}",
                        imageMime(JsonNodes.textAt(image, "image_url")),
                        approximateDataUrlBytes(JsonNodes.textAt(image, "image_url")),
                        JsonNodes.textAt(image, "_adapter_source").ifBlank { "cursor-payload" },
                    )
                    image.remove("_adapter_source")
                }
                continue
            }
            if (type == "text" || type == "input_text" || part.has("text")) {
                val text = JsonNodes.textAt(part, "text")
                if (text.isEmpty()) continue
                val mapped = objectMapper.createObjectNode()
                mapped.put("type", "input_text")
                mapped.put("text", text)
                out.add(mapped)
            }
        }
        return out
    }

    private fun mapImagePart(
        part: JsonNode,
        diskPaths: List<String>,
        imageIndex: Int,
    ): ObjectNode? {
        val imageNode = if (part.has("image_url")) part.path("image_url") else part
        val url =
            when {
                imageNode.isTextual -> JsonNodes.text(imageNode)
                imageNode.isObject -> JsonNodes.textAt(imageNode, "url")
                else -> JsonNodes.textAt(part, "image_url")
            }
        if (url.isBlank()) return null
        val resolved = cursorImageRecovery.resolveDataUrl(url, imageNode, diskPaths, imageIndex)
        val mapped = objectMapper.createObjectNode()
        mapped.put("type", "input_image")
        mapped.put("image_url", resolved.dataUrl)
        mapped.put("_adapter_source", resolved.source)
        val detail =
            when {
                imageNode.isObject && imageNode.has("detail") -> JsonNodes.textAt(imageNode, "detail")
                part.has("detail") -> JsonNodes.textAt(part, "detail")
                else -> "auto"
            }
        mapped.put("detail", detail.ifBlank { "auto" })
        return mapped
    }

    private fun imageMime(dataUrl: String): String {
        if (!dataUrl.startsWith("data:")) return "url"
        return dataUrl.substringAfter("data:").substringBefore(';').ifBlank { "unknown" }
    }

    private fun approximateDataUrlBytes(dataUrl: String): Int {
        val b64 = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
        if (b64.isEmpty()) return dataUrl.length
        return (b64.length * 3) / 4
    }

    private fun appendAssistant(
        message: JsonNode,
        input: ArrayNode,
        customCallIds: MutableSet<String>,
        customToolNames: Set<String>,
    ) {
        val text = flattenContent(message.path("content"))
        val toolCalls = message.path("tool_calls")
        val callIds = mutableListOf<String>()
        if (toolCalls.isArray) {
            for (toolCall in toolCalls) {
                callIds += CallIdNormalizer.normalize(JsonNodes.textAt(toolCall, "id"))
            }
        }

        val reasoning =
            when {
                callIds.isNotEmpty() -> reasoningStateCache.takeForCallIds(callIds)
                text.isNotEmpty() -> reasoningStateCache.takeForAssistantText(text)
                else -> emptyList()
            }
        if (reasoning.isNotEmpty()) {
            log.info(
                "Injecting {} cached reasoning item(s) callIds={} textLen={}",
                reasoning.size,
                callIds.size,
                text.length,
            )
            for (item in reasoning) input.add(item)
        } else if (callIds.isNotEmpty() || text.isNotEmpty()) {
            // Expected for history created before this process, after the
            // two-hour cache TTL, or by another client. The assistant/tool
            // history is still forwarded; only encrypted continuity is absent.
            log.debug(
                "No cached reasoning for assistant turn callIds={} textLen={} (store:false multi-turn may degrade)",
                callIds.size,
                text.length,
            )
        }

        if (text.isNotEmpty()) {
            val item = objectMapper.createObjectNode()
            item.put("role", "assistant")
            val contentArray = objectMapper.createArrayNode()
            val part = objectMapper.createObjectNode()
            part.put("type", "output_text")
            part.put("text", text)
            contentArray.add(part)
            item.set("content", contentArray)
            input.add(item)
        }

        if (!toolCalls.isArray) return
        for (toolCall in toolCalls) {
            val callId = CallIdNormalizer.normalize(JsonNodes.textAt(toolCall, "id"))
            val call = objectMapper.createObjectNode()
            val toolType = JsonNodes.textAt(toolCall, "type")
            val functionName = JsonNodes.textAt(toolCall.path("function"), "name")
            val customName =
                JsonNodes.textAt(toolCall.path("custom"), "name").ifBlank {
                    JsonNodes.textAt(toolCall, "name")
                }
            val asCustom =
                toolType == "custom" ||
                    toolCall.has("custom") ||
                    functionName in customToolNames ||
                    customName in customToolNames
            if (asCustom) {
                customCallIds += callId
                call.put("type", "custom_tool_call")
                call.put("call_id", callId)
                val name = customName.ifBlank { functionName }.ifBlank { "ApplyPatch" }
                call.put("name", name)
                call.put(
                    "input",
                    if (toolCall.has("custom")) {
                        JsonNodes.textAt(toolCall.path("custom"), "input")
                    } else {
                        freeformInputFromFunctionArgs(
                            JsonNodes.jsonTextAt(toolCall.path("function"), "arguments", ""),
                        )
                    },
                )
            } else {
                call.put("type", "function_call")
                call.put("call_id", callId)
                call.put("name", functionName)
                call.put("arguments", JsonNodes.jsonTextAt(toolCall.path("function"), "arguments", "{}"))
            }
            input.add(call)
        }
    }

    private fun customToolNames(tools: JsonNode): Set<String> {
        if (!tools.isArray) return setOf("ApplyPatch")
        val names =
            tools.mapNotNull { tool ->
                if (JsonNodes.textAt(tool, "type") != "custom") return@mapNotNull null
                JsonNodes.textAt(tool, "name").ifBlank { null }
            }.toSet()
        return names + "ApplyPatch"
    }

    private fun freeformInputFromFunctionArgs(arguments: String): String {
        if (arguments.isBlank()) return ""
        // Already a patch body (Cursor echoes function.arguments as freeform text).
        if (arguments.trimStart().startsWith("***")) return arguments
        val parsed = runCatching { objectMapper.readTree(arguments) }.getOrNull() ?: return arguments
        if (parsed.isTextual) return JsonNodes.text(parsed)
        if (parsed.isObject) {
            for (key in listOf("input", "patch", "patchText", "content")) {
                val value = parsed.path(key)
                if (value.isTextual) return JsonNodes.text(value)
            }
        }
        return arguments
    }

    private fun appendToolResult(
        message: JsonNode,
        input: ArrayNode,
        customCallIds: Set<String>,
    ) {
        val callId = CallIdNormalizer.normalize(JsonNodes.textAt(message, "tool_call_id"))
        val item = objectMapper.createObjectNode()
        if (callId in customCallIds) {
            item.put("type", "custom_tool_call_output")
        } else {
            item.put("type", "function_call_output")
        }
        item.put("call_id", callId)

        val content = message.path("content")
        val hasImage =
            content.isArray &&
                content.any { part ->
                    val type = JsonNodes.textAt(part, "type")
                    type == "image_url" || type == "input_image" || part.has("image_url")
                }
        if (hasImage) {
            // Codex tool outputs are strings; keep text in output and attach images as a
            // follow-up user turn so vision still works for screenshot tool results.
            item.put("output", flattenContent(content).ifBlank { "[image tool result]" })
            input.add(item)
            val imageParts = objectMapper.createArrayNode()
            var imageIndex = 0
            val diskPaths = cursorImageRecovery.extractDiskPaths(content)
            for (part in content) {
                val type = JsonNodes.textAt(part, "type")
                if (type == "image_url" || type == "input_image" || part.has("image_url")) {
                    mapImagePart(part, diskPaths, imageIndex)?.let { mapped ->
                        mapped.remove("_adapter_source")
                        imageParts.add(mapped)
                    }
                    imageIndex += 1
                }
            }
            if (!imageParts.isEmpty) {
                val followUp = objectMapper.createObjectNode()
                followUp.put("role", "user")
                followUp.set("content", imageParts)
                input.add(followUp)
            }
            return
        }

        item.put("output", flattenContent(content))
        input.add(item)
    }

    private fun applyReasoning(
        chatRequest: JsonNode,
        out: ObjectNode,
    ) {
        if (chatRequest.has("reasoning") && chatRequest.path("reasoning").isObject) {
            out.set("reasoning", chatRequest.path("reasoning"))
            return
        }
        val effort = ReasoningEffortResolver.fromRequest(chatRequest)
        if (effort.isNullOrBlank()) return
        val reasoning = objectMapper.createObjectNode()
        reasoning.put("effort", effort)
        out.set("reasoning", reasoning)
    }

    private fun normalizeTools(tools: JsonNode): ArrayNode {
        val out = objectMapper.createArrayNode()
        if (!tools.isArray) return out
        for (tool in tools) {
            if (JsonNodes.textAt(tool, "type") == "function" && tool.has("function")) {
                val fn = tool.path("function")
                val mapped = objectMapper.createObjectNode()
                mapped.put("type", "function")
                mapped.put("name", JsonNodes.textAt(fn, "name"))
                if (fn.has("description")) mapped.put("description", JsonNodes.textAt(fn, "description"))
                if (fn.has("parameters")) mapped.set("parameters", fn.path("parameters"))
                mapped.put("strict", false)
                out.add(mapped)
            } else {
                out.add(tool.deepCopy())
            }
        }
        return out
    }

    private fun normalizeToolChoice(toolChoice: JsonNode): JsonNode {
        if (toolChoice.isTextual) return toolChoice
        if (toolChoice.isObject && JsonNodes.textAt(toolChoice, "type") == "function") {
            val name =
                if (toolChoice.has("function")) JsonNodes.textAt(toolChoice.path("function"), "name")
                else JsonNodes.textAt(toolChoice, "name")
            if (name.isNotBlank()) {
                val mapped = objectMapper.createObjectNode()
                mapped.put("type", "function")
                mapped.put("name", name)
                return mapped
            }
        }
        return toolChoice
    }

    private fun flattenContent(content: JsonNode): String {
        if (content.isTextual) return JsonNodes.text(content)
        if (content.isArray) {
            return content.joinToString("\n") { part ->
                when {
                    part.isTextual -> JsonNodes.text(part)
                    part.has("text") -> JsonNodes.textAt(part, "text")
                    else -> ""
                }
            }
        }
        if (content.isMissingNode || content.isNull) return ""
        return content.toString()
    }
}
