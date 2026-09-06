package com.opengpt.streaming

import com.opengpt.codex.CallIdNormalizer
import com.opengpt.util.JsonNodes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

@Component
class StreamTranslator(
    private val objectMapper: ObjectMapper,
) {
    fun newSession(model: String): Session = Session(objectMapper, model)

    class Session(
        private val objectMapper: ObjectMapper,
        private val model: String,
    ) {
        private val log = LoggerFactory.getLogger(StreamTranslator::class.java)
        private val completionId = "chatcmpl-${UUID.randomUUID()}"
        private var sentRole = false
        private var sawText = false
        private var sawToolCall = false
        private var finishReasonValue = "stop"
        private val textBuilder = StringBuilder()
        private var usage: ObjectNode? = null
        private val capturedReasoningItems = mutableListOf<ObjectNode>()
        private val completedCallIds = linkedSetOf<String>()

        // Responses output_index / item_id are NOT Chat Completions tool_calls[].index.
        // Track dense chat indexes keyed by Responses item_id and call_id.
        private val toolIndexByItemId = linkedMapOf<String, Int>()
        private val toolIndexByCallId = linkedMapOf<String, Int>()
        private val pendingCustomByIndex = linkedMapOf<Int, PendingCustomTool>()
        private val pendingCustomByItemId = linkedMapOf<String, PendingCustomTool>()
        private val pendingFunctionByIndex = linkedMapOf<Int, PendingFunctionTool>()
        private val pendingFunctionByItemId = linkedMapOf<String, PendingFunctionTool>()
        private val suppressedEmptyTools = mutableListOf<SuppressedEmptyTool>()
        private var emptyToolRetryUsed = false
        private var deferFinishForEmptyRetry = false

        private class PendingCustomTool(
            val index: Int,
            val callId: String,
            var name: String,
            val input: StringBuilder = StringBuilder(),
            var emitted: Boolean = false,
        )

        private class PendingFunctionTool(
            val index: Int,
            val callId: String,
            var name: String,
            val arguments: StringBuilder = StringBuilder(),
            var emitted: Boolean = false,
        )

        data class SuppressedEmptyTool(
            val callId: String,
            val name: String,
            val arguments: String,
        )

        fun toChatCompletionChunk(event: JsonNode): List<ObjectNode> {
            val type = JsonNodes.textAt(event, "type")
            return when (type) {
                "response.created", "response.in_progress" -> {
                    if (sentRole) emptyList()
                    else {
                        sentRole = true
                        listOf(roleChunk())
                    }
                }
                "response.output_text.delta" -> {
                    val text = JsonNodes.textAt(event, "delta")
                    if (text.isEmpty()) emptyList()
                    else {
                        sawText = true
                        textBuilder.append(text)
                        val out = mutableListOf<ObjectNode>()
                        if (!sentRole) {
                            sentRole = true
                            out += roleChunk()
                        }
                        out += textDelta(text)
                        out
                    }
                }
                "response.output_item.added" -> toolCallStarted(event)
                "response.function_call_arguments.delta" -> {
                    bufferFunctionToolDelta(event)
                    emptyList()
                }
                "response.custom_tool_call_input.delta" -> {
                    // Buffer custom input. Cursor's chat-completions proxy Internal Errors on
                    // thousands of tiny custom.input deltas (ApplyPatch), then retries mid-stream.
                    bufferCustomToolDelta(event)
                    emptyList()
                }
                "response.output_item.done",
                "response.function_call_arguments.done",
                "response.custom_tool_call_input.done",
                -> flushToolDone(event)
                "response.completed", "response.done", "response.incomplete" -> {
                    captureUsage(event)
                    val flushed = flushAllPendingTools()
                    if (shouldDeferFinishForEmptyRetry()) {
                        deferFinishForEmptyRetry = true
                        return flushed
                    }
                    val reason = if (sawToolCall) "tool_calls" else "stop"
                    finishReasonValue = reason
                    flushed + finishChunks(reason)
                }
                "response.failed", "error" -> {
                    captureUsage(event)
                    val flushed = flushAllPendingTools()
                    val reason = if (sawToolCall) "tool_calls" else "stop"
                    finishReasonValue = reason
                    flushed + finishChunks(reason)
                }
                else -> emptyList()
            }
        }

        fun hadContent(): Boolean = sawText || sawToolCall

        fun lastUsage(): ObjectNode? = usage

        fun reasoningItems(): List<ObjectNode> = capturedReasoningItems.toList()

        fun toolCallIds(): List<String> = completedCallIds.toList()

        fun assistantText(): String = textBuilder.toString()

        fun finishReason(): String = finishReasonValue

        fun suppressedEmptyTools(): List<SuppressedEmptyTool> = suppressedEmptyTools.toList()

        fun needsEmptyToolRetry(): Boolean = deferFinishForEmptyRetry

        fun markEmptyToolRetryStarted() {
            emptyToolRetryUsed = true
            deferFinishForEmptyRetry = false
            suppressedEmptyTools.clear()
        }

        private fun shouldDeferFinishForEmptyRetry(): Boolean {
            if (emptyToolRetryUsed) return false
            if (sawText || sawToolCall) return false
            return suppressedEmptyTools.isNotEmpty()
        }

        private fun finishChunks(reason: String): List<ObjectNode> {
            val out = mutableListOf(finish(reason))
            usage?.let { out += usageChunk(it) }
            return out
        }

        private fun captureUsage(event: JsonNode) {
            val raw =
                event.path("response").path("usage").takeIf { !it.isMissingNode && !it.isNull }
                    ?: event.path("usage").takeIf { !it.isMissingNode && !it.isNull }
                    ?: return
            usage = toChatUsage(raw)
        }

        private fun toChatUsage(raw: JsonNode): ObjectNode {
            val prompt = intField(raw, "input_tokens", "inputTokens", "prompt_tokens", "promptTokens")
            val completion = intField(raw, "output_tokens", "outputTokens", "completion_tokens", "completionTokens")
            val total =
                intField(raw, "total_tokens", "totalTokens")
                    ?: listOfNotNull(prompt, completion).takeIf { it.isNotEmpty() }?.sum()

            val out = objectMapper.createObjectNode()
            if (prompt != null) out.put("prompt_tokens", prompt)
            if (completion != null) out.put("completion_tokens", completion)
            if (total != null) out.put("total_tokens", total)

            val reasoning =
                raw.path("output_tokens_details").takeIf { !it.isMissingNode && !it.isNull }?.let {
                    intField(it, "reasoning_tokens", "reasoningTokens")
                } ?: intField(raw, "reasoning_tokens", "reasoningTokens")
            if (reasoning != null) {
                val details = objectMapper.createObjectNode()
                details.put("reasoning_tokens", reasoning)
                out.set("completion_tokens_details", details)
            }

            val cached =
                raw.path("input_tokens_details").takeIf { !it.isMissingNode && !it.isNull }?.let {
                    intField(it, "cached_tokens", "cachedTokens")
                } ?: intField(raw, "cached_tokens", "cachedTokens")
            if (cached != null) {
                val details = objectMapper.createObjectNode()
                details.put("cached_tokens", cached)
                out.set("prompt_tokens_details", details)
            }
            return out
        }

        private fun intField(node: JsonNode, vararg fields: String): Int? {
            for (field in fields) {
                val value = node.path(field)
                if (!value.isMissingNode && !value.isNull && value.canConvertToInt()) {
                    return value.asInt()
                }
            }
            return null
        }

        private fun roleChunk(): ObjectNode {
            val root = baseChunk()
            val choice = objectMapper.createObjectNode()
            choice.put("index", 0)
            val delta = objectMapper.createObjectNode()
            delta.put("role", "assistant")
            choice.set("delta", delta)
            choice.putNull("finish_reason")
            root.putArray("choices").add(choice)
            return root
        }

        private fun textDelta(text: String): ObjectNode {
            val root = baseChunk()
            val choice = objectMapper.createObjectNode()
            choice.put("index", 0)
            val delta = objectMapper.createObjectNode()
            delta.put("content", text)
            choice.set("delta", delta)
            choice.putNull("finish_reason")
            root.putArray("choices").add(choice)
            return root
        }

        private fun finish(reason: String): ObjectNode {
            val root = baseChunk()
            val choice = objectMapper.createObjectNode()
            choice.put("index", 0)
            choice.set("delta", objectMapper.createObjectNode())
            choice.put("finish_reason", reason)
            root.putArray("choices").add(choice)
            return root
        }

        private fun usageChunk(usageNode: ObjectNode): ObjectNode {
            val root = baseChunk()
            root.putArray("choices")
            root.set("usage", usageNode)
            return root
        }

        private fun toolCallStarted(event: JsonNode): List<ObjectNode> {
            val item = event.path("item")
            val itemType = JsonNodes.textAt(item, "type")
            val isFunction = itemType == "function_call"
            val isCustom = itemType == "custom_tool_call"
            if (!isFunction && !isCustom) return emptyList()

            val itemId = JsonNodes.textAt(item, "id")
            val callId = CallIdNormalizer.normalize(JsonNodes.textAt(item, "call_id", itemId))
            val name = JsonNodes.textAt(item, "name")
            val index = registerTool(itemId = itemId, callId = callId)

            if (isCustom) {
                val pending =
                    PendingCustomTool(
                        index = index,
                        callId = callId,
                        name = name,
                    )
                val initial = JsonNodes.textAt(item, "input")
                if (initial.isNotEmpty()) pending.input.append(initial)
                pendingCustomByIndex[index] = pending
                if (itemId.isNotBlank()) pendingCustomByItemId[itemId] = pending
                // Defer emission until input is complete (see custom delta buffering).
                return if (!sentRole) {
                    sentRole = true
                    listOf(roleChunk())
                } else {
                    emptyList()
                }
            }

            val pending =
                PendingFunctionTool(
                    index = index,
                    callId = callId,
                    name = name,
                )
            val initial = JsonNodes.textAt(item, "arguments")
            if (initial.isNotEmpty()) pending.arguments.append(initial)
            pendingFunctionByIndex[index] = pending
            if (itemId.isNotBlank()) pendingFunctionByItemId[itemId] = pending
            // Buffer until arguments are complete so empty `{}` calls never reach Cursor.
            return if (!sentRole) {
                sentRole = true
                listOf(roleChunk())
            } else {
                emptyList()
            }
        }

        private fun bufferFunctionToolDelta(event: JsonNode) {
            val text = JsonNodes.textAt(event, "delta")
            if (text.isEmpty()) return
            val pending = resolvePendingFunction(event) ?: return
            pending.arguments.append(text)
        }

        private fun bufferCustomToolDelta(event: JsonNode) {
            val text = JsonNodes.textAt(event, "delta")
            if (text.isEmpty()) return
            val itemId = JsonNodes.textAt(event, "item_id")
            val callId = CallIdNormalizer.normalize(JsonNodes.textAt(event, "call_id"))
            val pending =
                pendingCustomByItemId[itemId]
                    ?: callId.takeIf { it.isNotBlank() }?.let { id ->
                        pendingCustomByIndex.values.firstOrNull { it.callId == id }
                    }
                    ?: run {
                        val index =
                            resolveToolIndex(itemId = itemId, callId = callId)
                                ?: registerTool(itemId = itemId, callId = callId)
                        PendingCustomTool(index = index, callId = callId, name = "").also {
                            pendingCustomByIndex[index] = it
                            if (itemId.isNotBlank()) pendingCustomByItemId[itemId] = it
                        }
                    }
            pending.input.append(text)
        }

        private fun flushToolDone(event: JsonNode): List<ObjectNode> {
            val item = event.path("item")
            if (!item.isMissingNode && !item.isNull) {
                val itemType = JsonNodes.textAt(item, "type")
                if (itemType == "reasoning") {
                    captureReasoningItem(item)
                    return emptyList()
                }
                if (itemType == "custom_tool_call") {
                    val itemId = JsonNodes.textAt(item, "id")
                    val callId = CallIdNormalizer.normalize(JsonNodes.textAt(item, "call_id"))
                    val index =
                        resolveToolIndex(itemId = itemId, callId = callId)
                            ?: registerTool(itemId = itemId, callId = callId)
                    val pending =
                        pendingCustomByItemId[itemId]
                            ?: pendingCustomByIndex[index]
                            ?: PendingCustomTool(
                                index = index,
                                callId = callId,
                                name = JsonNodes.textAt(item, "name"),
                            ).also {
                                pendingCustomByIndex[index] = it
                                if (itemId.isNotBlank()) pendingCustomByItemId[itemId] = it
                            }
                    val name = JsonNodes.textAt(item, "name")
                    if (pending.name.isBlank() && name.isNotBlank()) pending.name = name
                    val fullInput = JsonNodes.textAt(item, "input").ifBlank { pending.input.toString() }
                    return listOfNotNull(emitCompletedCustomTool(pending, fullInput))
                }
                if (itemType == "function_call") {
                    val itemId = JsonNodes.textAt(item, "id")
                    val callId = CallIdNormalizer.normalize(JsonNodes.textAt(item, "call_id"))
                    val index =
                        resolveToolIndex(itemId = itemId, callId = callId)
                            ?: registerTool(itemId = itemId, callId = callId)
                    val pending =
                        pendingFunctionByItemId[itemId]
                            ?: pendingFunctionByIndex[index]
                            ?: PendingFunctionTool(
                                index = index,
                                callId = callId,
                                name = JsonNodes.textAt(item, "name"),
                            ).also {
                                pendingFunctionByIndex[index] = it
                                if (itemId.isNotBlank()) pendingFunctionByItemId[itemId] = it
                            }
                    val name = JsonNodes.textAt(item, "name")
                    if (pending.name.isBlank() && name.isNotBlank()) pending.name = name
                    val fullArgs =
                        JsonNodes.textAt(item, "arguments").ifBlank { pending.arguments.toString() }
                    return listOfNotNull(emitCompletedFunctionTool(pending, fullArgs))
                }
            }

            val eventType = JsonNodes.textAt(event, "type")
            if (eventType == "response.custom_tool_call_input.done") {
                val itemId = JsonNodes.textAt(event, "item_id")
                val callId = CallIdNormalizer.normalize(JsonNodes.textAt(event, "call_id"))
                val pending =
                    pendingCustomByItemId[itemId]
                        ?: pendingCustomByIndex.values.firstOrNull { callId.isNotBlank() && it.callId == callId }
                if (pending != null) {
                    val fullInput = JsonNodes.textAt(event, "input").ifBlank { pending.input.toString() }
                    return listOfNotNull(emitCompletedCustomTool(pending, fullInput))
                }
            }

            if (eventType == "response.function_call_arguments.done") {
                val pending = resolvePendingFunction(event)
                if (pending != null) {
                    val fullArgs =
                        JsonNodes.textAt(event, "arguments").ifBlank { pending.arguments.toString() }
                    return listOfNotNull(emitCompletedFunctionTool(pending, fullArgs))
                }
            }

            return emptyList()
        }

        private fun flushAllPendingTools(): List<ObjectNode> {
            val custom =
                pendingCustomByIndex.values.mapNotNull { pending ->
                    if (pending.emitted) null
                    else emitCompletedCustomTool(pending, pending.input.toString())
                }
            val functions =
                pendingFunctionByIndex.values.mapNotNull { pending ->
                    if (pending.emitted) null
                    else emitCompletedFunctionTool(pending, pending.arguments.toString())
                }
            return custom + functions
        }

        private fun resolvePendingFunction(event: JsonNode): PendingFunctionTool? {
            val itemId = JsonNodes.textAt(event, "item_id")
            val callId = CallIdNormalizer.normalize(JsonNodes.textAt(event, "call_id"))
            pendingFunctionByItemId[itemId]?.let { return it }
            if (callId.isNotBlank()) {
                pendingFunctionByIndex.values.firstOrNull { it.callId == callId }?.let { return it }
            }
            val index =
                resolveToolIndex(itemId = itemId, callId = callId)
                    ?: registerTool(itemId = itemId, callId = callId)
            return PendingFunctionTool(index = index, callId = callId, name = "").also {
                pendingFunctionByIndex[index] = it
                if (itemId.isNotBlank()) pendingFunctionByItemId[itemId] = it
            }
        }

        /**
         * Emit one Chat Completions tool_call for a finished Codex custom tool.
         *
         * Cursor's OpenAI-compatible proxy strips `type:"custom"` tool_calls from the
         * assistant message (traffic shows the next turn keeps text but `tool_calls=[]`),
         * which aborts ApplyPatch and triggers Internal Error / retries. Lower custom
         * tools to `type:"function"` with `arguments` set to the raw freeform patch
         * text (Cursor's ApplyPatch editor reads arguments as the patch body; wrapping
         * as `{"input":...}` causes "Missing *** Add/Update File header"). History
         * mapping promotes ApplyPatch back to Codex `custom_tool_call`.
         */
        private fun emitCompletedCustomTool(
            pending: PendingCustomTool,
            fullInput: String,
        ): ObjectNode? {
            if (pending.emitted) return null
            pending.emitted = true
            pending.input.clear()
            pending.input.append(fullInput)
            sawToolCall = true
            if (pending.callId.isNotBlank()) completedCallIds += pending.callId

            val root = baseChunk()
            val choice = objectMapper.createObjectNode()
            choice.put("index", 0)
            val delta = objectMapper.createObjectNode()
            if (!sentRole) {
                sentRole = true
                delta.put("role", "assistant")
            }
            val toolCalls = objectMapper.createArrayNode()
            val toolCall = objectMapper.createObjectNode()
            toolCall.put("index", pending.index)
            toolCall.put("id", pending.callId)
            toolCall.put("type", "function")
            val function = objectMapper.createObjectNode()
            val name = pending.name.ifBlank { "ApplyPatch" }
            function.put("name", name)
            // Cursor ApplyPatch treats function.arguments as the freeform patch body
            // (not a JSON object). Wrapping as {"input":"..."} yields:
            // "Error: Missing *** Add/Update File header."
            function.put("arguments", fullInput)
            toolCall.set("function", function)
            toolCalls.add(toolCall)
            delta.set("tool_calls", toolCalls)
            choice.set("delta", delta)
            choice.putNull("finish_reason")
            root.putArray("choices").add(choice)
            return root
        }

        private fun emitCompletedFunctionTool(
            pending: PendingFunctionTool,
            fullArgs: String,
        ): ObjectNode? {
            if (pending.emitted) return null
            pending.emitted = true
            pending.arguments.clear()
            pending.arguments.append(fullArgs)

            if (!usableFunctionArguments(fullArgs)) {
                log.info(
                    "Suppressing empty function tool call name={} callId={} args={}",
                    pending.name,
                    pending.callId,
                    fullArgs.take(80),
                )
                suppressedEmptyTools +=
                    SuppressedEmptyTool(
                        callId = pending.callId,
                        name = pending.name.ifBlank { "unknown_tool" },
                        arguments = fullArgs.ifBlank { "{}" },
                    )
                return null
            }

            sawToolCall = true
            if (pending.callId.isNotBlank()) completedCallIds += pending.callId

            val root = baseChunk()
            val choice = objectMapper.createObjectNode()
            choice.put("index", 0)
            val delta = objectMapper.createObjectNode()
            if (!sentRole) {
                sentRole = true
                delta.put("role", "assistant")
            }
            val toolCalls = objectMapper.createArrayNode()
            val toolCall = objectMapper.createObjectNode()
            toolCall.put("index", pending.index)
            toolCall.put("id", pending.callId)
            toolCall.put("type", "function")
            val function = objectMapper.createObjectNode()
            function.put("name", pending.name)
            function.put("arguments", fullArgs)
            toolCall.set("function", function)
            toolCalls.add(toolCall)
            delta.set("tool_calls", toolCalls)
            choice.set("delta", delta)
            choice.putNull("finish_reason")
            root.putArray("choices").add(choice)
            return root
        }

        private fun captureReasoningItem(item: JsonNode) {
            val encrypted = JsonNodes.textAt(item, "encrypted_content")
            if (encrypted.isBlank()) return
            val id = JsonNodes.textAt(item, "id")
            if (id.isBlank()) return
            if (capturedReasoningItems.any { JsonNodes.textAt(it, "id") == id }) return

            val stored = objectMapper.createObjectNode()
            stored.put("type", "reasoning")
            stored.put("id", id)
            stored.put("encrypted_content", encrypted)
            val summary = item.path("summary")
            if (summary.isArray) {
                stored.set("summary", summary.deepCopy())
            } else {
                stored.putArray("summary")
            }
            capturedReasoningItems += stored
        }

        private fun registerTool(
            itemId: String,
            callId: String,
        ): Int {
            resolveToolIndex(itemId = itemId, callId = callId)?.let { return it }
            val index = toolIndexByItemId.size.coerceAtLeast(toolIndexByCallId.size)
            if (itemId.isNotBlank()) toolIndexByItemId[itemId] = index
            if (callId.isNotBlank()) toolIndexByCallId[callId] = index
            if (itemId.isBlank() && callId.isBlank()) {
                toolIndexByItemId["anon-$index"] = index
            }
            return index
        }

        private fun resolveToolIndex(
            itemId: String,
            callId: String,
        ): Int? {
            if (itemId.isNotBlank()) toolIndexByItemId[itemId]?.let { return it }
            if (callId.isNotBlank()) toolIndexByCallId[callId]?.let { return it }
            return null
        }

        private fun baseChunk(): ObjectNode {
            val root = objectMapper.createObjectNode()
            root.put("id", completionId)
            root.put("object", "chat.completion.chunk")
            root.put("created", System.currentTimeMillis() / 1000)
            root.put("model", model)
            return root
        }

        private fun usableFunctionArguments(args: String): Boolean {
            val trimmed = args.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed == "{}" || trimmed == "[]" || trimmed == "null") return false
            val parsed = runCatching { objectMapper.readTree(trimmed) }.getOrNull() ?: return true
            if (parsed.isNull) return false
            if (parsed.isObject && parsed.size() == 0) return false
            if (parsed.isArray && parsed.size() == 0) return false
            return true
        }
    }
}
