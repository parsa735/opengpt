package com.opengpt.streaming

import com.opengpt.codex.CodexClient
import com.opengpt.codex.ModelResolver
import com.opengpt.codex.ReasoningEffortResolver
import com.opengpt.codex.ReasoningStateCache
import com.opengpt.debug.TrafficDebugLog
import com.opengpt.util.JsonNodes
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class SseProxy(
    private val codexClient: CodexClient,
    private val objectMapper: ObjectMapper,
    private val streamTranslator: StreamTranslator,
    private val modelResolver: ModelResolver,
    private val reasoningStateCache: ReasoningStateCache,
    private val trafficDebugLog: TrafficDebugLog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun prepareResponsesBody(request: JsonNode): ObjectNode {
        val copy =
            if (request is ObjectNode) {
                request.deepCopy() as ObjectNode
            } else {
                objectMapper.valueToTree(request)
            }
        normalizeExplicitReasoningEffort(copy)
        val requested = JsonNodes.textAt(copy, "model")
        val resolved = modelResolver.resolve(requested)
        if (requested != resolved.model || resolved.reasoningEffort != null) {
            log.info(
                "Resolved client model '{}' -> model='{}' reasoningEffort={}",
                requested,
                resolved.model,
                resolved.reasoningEffort,
            )
        }
        copy.put("model", resolved.model)
        applyResolvedEffort(copy, resolved)
        normalizeModelSpecificEffort(copy, resolved.model)
        ensureDefaultReasoningEffort(copy, resolved.model)
        ensureReasoningSummary(copy)
        ensureEncryptedReasoningInclude(copy)
        ensureParallelToolCalls(copy)
        copy.put("stream", true)
        if (!copy.has("store")) copy.put("store", false)
        return copy
    }

    private fun ensureParallelToolCalls(copy: ObjectNode) {
        val tools = copy.path("tools")
        val hasTools = tools.isArray && tools.size() > 0
        if (copy.has("parallel_tool_calls") && !copy.path("parallel_tool_calls").isNull) return
        if (hasTools) copy.put("parallel_tool_calls", true)
    }

    private fun normalizeExplicitReasoningEffort(copy: ObjectNode) {
        val effort = ReasoningEffortResolver.fromRequest(copy)
        copy.remove("reasoning_effort")
        copy.remove("reasoningEffort")
        copy.remove("cursor_model_params")
        if (effort == null) return

        val reasoning =
            if (copy.path("reasoning").isObject) {
                copy.path("reasoning") as ObjectNode
            } else {
                objectMapper.createObjectNode().also { copy.set("reasoning", it) }
            }
        reasoning.put("effort", effort)
        log.info("Applied explicit client reasoning.effort={}", effort)
    }

    private fun applyResolvedEffort(
        copy: ObjectNode,
        resolved: ModelResolver.Resolved,
    ) {
        if (resolved.reasoningEffort == null) return
        val reasoning =
            if (copy.path("reasoning").isObject) {
                copy.path("reasoning") as ObjectNode
            } else {
                objectMapper.createObjectNode().also { copy.set("reasoning", it) }
            }
        if (JsonNodes.textAt(reasoning, "effort").isBlank()) {
            reasoning.put("effort", resolved.reasoningEffort)
        }
    }

    private fun normalizeModelSpecificEffort(
        copy: ObjectNode,
        model: String,
    ) {
        if (model != "gpt-6-astra") return
        val reasoning = copy.path("reasoning") as? ObjectNode ?: return
        val effort = JsonNodes.textAt(reasoning, "effort").lowercase()
        if (effort == "none" || effort == "minimal") {
            reasoning.remove("effort")
            log.info("Removed unsupported reasoning.effort={} for model={}; applying default", effort, model)
        }
    }

    /**
     * Cursor "Extra High" often does not send reasoning_effort to custom base URLs.
     * Default xhigh for GPT-5.6 Codex models and high for Astra.
     */
    private fun ensureDefaultReasoningEffort(
        copy: ObjectNode,
        model: String,
    ) {
        val reasoning =
            if (copy.path("reasoning").isObject) {
                copy.path("reasoning") as ObjectNode
            } else {
                objectMapper.createObjectNode().also { copy.set("reasoning", it) }
            }
        if (JsonNodes.textAt(reasoning, "effort").isNotBlank()) return
        val effort = ModelResolver.defaultEffortFor(model)
        reasoning.put("effort", effort)
        log.info("Defaulted reasoning.effort={} for model={}", effort, model)
    }

    private fun ensureReasoningSummary(copy: ObjectNode) {
        if (!copy.has("reasoning") || copy.path("reasoning").isNull) {
            val reasoning = objectMapper.createObjectNode()
            reasoning.put("summary", "auto")
            copy.set("reasoning", reasoning)
            return
        }
        if (copy.path("reasoning").isObject) {
            val reasoning = copy.path("reasoning") as ObjectNode
            if (!reasoning.has("summary")) reasoning.put("summary", "auto")
        }
    }

    private fun ensureEncryptedReasoningInclude(copy: ObjectNode) {
        val include = copy.withArrayProperty("include")
        val already =
            include.any { node ->
                JsonNodes.text(node) == "reasoning.encrypted_content"
            }
        if (!already) include.add("reasoning.encrypted_content")
    }

    fun proxyResponsesSse(
        request: JsonNode,
        exchangeId: Long = 0,
    ): Flux<String> {
        val body = prepareResponsesBody(request)
        if (exchangeId > 0) {
            trafficDebugLog.write(exchangeId, "CODEX_REQUEST", objectMapper.writeValueAsString(body))
        }
        val codexRaw = StringBuilder()
        return decodeSseEvents(codexClient.streamResponse(body), codexRaw)
            .doOnNext { payload -> logCodexTelemetry(payload) }
            .map { payload -> "data: $payload\n\n" }
            .doFinally {
                if (exchangeId > 0) {
                    trafficDebugLog.write(exchangeId, "CODEX_RESPONSE_SSE", codexRaw.toString())
                }
            }
    }

    fun proxyAsChatCompletionsSse(
        request: JsonNode,
        exchangeId: Long = 0,
    ): Flux<String> {
        val body = prepareResponsesBody(request)
        val model = JsonNodes.textAt(body, "model", ModelResolver.DEFAULT)
        val session = streamTranslator.newSession(model)
        if (exchangeId > 0) {
            trafficDebugLog.write(exchangeId, "CODEX_REQUEST", objectMapper.writeValueAsString(body))
        }
        log.info(
            "Chat completions -> Codex exchange={} model={} include={} reasoning={} parallelToolCalls={}",
            exchangeId,
            model,
            body.path("include"),
            body.path("reasoning"),
            body.path("parallel_tool_calls"),
        )
        val codexRaw = StringBuilder()
        val translated =
            translateCodexStream(body, session, codexRaw)
                .concatWith(
                    Flux.defer {
                        if (!session.needsEmptyToolRetry()) return@defer Flux.empty()
                        val suppressed = session.suppressedEmptyTools()
                        log.info(
                            "Retrying Codex after suppressing {} empty tool call(s) exchange={}",
                            suppressed.size,
                            exchangeId,
                        )
                        val retryBody = buildEmptyToolRetryRequest(body, session, suppressed)
                        session.markEmptyToolRetryStarted()
                        if (exchangeId > 0) {
                            trafficDebugLog.write(
                                exchangeId,
                                "CODEX_REQUEST_EMPTY_TOOL_RETRY",
                                objectMapper.writeValueAsString(retryBody),
                            )
                        }
                        translateCodexStream(retryBody, session, codexRaw)
                    },
                )
                .publish()
                .refCount()

        // Cursor aborts/retries after ~50s of silence once text stops and while we buffer
        // reasoning / ApplyPatch. SSE comments are often stripped by proxies, so emit a
        // no-op chat.completion.chunk instead.
        val keepAlive =
            Flux.interval(Duration.ofSeconds(10))
                .map {
                    val root = objectMapper.createObjectNode()
                    root.put("id", "chatcmpl-keepalive")
                    root.put("object", "chat.completion.chunk")
                    root.put("created", System.currentTimeMillis() / 1000)
                    root.put("model", model)
                    val choice = objectMapper.createObjectNode()
                    choice.put("index", 0)
                    choice.set("delta", objectMapper.createObjectNode())
                    choice.putNull("finish_reason")
                    root.putArray("choices").add(choice)
                    "data: ${objectMapper.writeValueAsString(root)}\n\n"
                }
                .takeUntilOther(translated.ignoreElements())

        return Flux.merge(translated, keepAlive)
            .concatWith(Flux.just("data: [DONE]\n\n"))
            .doOnComplete {
                reasoningStateCache.rememberTurn(
                    session.toolCallIds(),
                    session.assistantText(),
                    session.reasoningItems(),
                )
                if (!session.hadContent()) {
                    log.warn("Codex stream finished with no assistant text/tools for model={}", model)
                } else {
                    log.info(
                        "Chat completions stream complete model={} toolCalls={} reasoningItems={} textChars={} finishReason={}",
                        model,
                        session.toolCallIds().size,
                        session.reasoningItems().size,
                        session.assistantText().length,
                        session.finishReason(),
                    )
                }
            }
            .doFinally {
                if (exchangeId > 0) {
                    trafficDebugLog.write(exchangeId, "CODEX_RESPONSE_SSE", codexRaw.toString())
                }
            }
    }

    private fun translateCodexStream(
        body: ObjectNode,
        session: StreamTranslator.Session,
        codexRaw: StringBuilder,
    ): Flux<String> =
        decodeSseEvents(codexClient.streamResponse(body), codexRaw)
            .doOnNext { payload -> logCodexTelemetry(payload) }
            .concatMap { payload ->
                try {
                    val event = runCatching { objectMapper.readTree(payload) }.getOrNull()
                        ?: return@concatMap Flux.empty()
                    val chunks = session.toChatCompletionChunk(event)
                    Flux.fromIterable(chunks.map { "data: ${objectMapper.writeValueAsString(it)}\n\n" })
                } catch (error: Exception) {
                    log.warn(
                        "Failed translating Codex SSE event: {} payload={}",
                        error.message,
                        payload.take(300),
                    )
                    Flux.empty()
                }
            }

    private fun buildEmptyToolRetryRequest(
        original: ObjectNode,
        session: StreamTranslator.Session,
        suppressed: List<StreamTranslator.Session.SuppressedEmptyTool>,
    ): ObjectNode {
        val retry = original.deepCopy() as ObjectNode
        val input =
            if (retry.path("input").isArray) {
                retry.path("input").deepCopy() as tools.jackson.databind.node.ArrayNode
            } else {
                objectMapper.createArrayNode()
            }

        for (item in session.reasoningItems()) {
            input.add(item.deepCopy())
        }
        for (tool in suppressed) {
            val call = objectMapper.createObjectNode()
            call.put("type", "function_call")
            call.put("call_id", tool.callId)
            call.put("name", tool.name)
            call.put("arguments", tool.arguments)
            input.add(call)

            val output = objectMapper.createObjectNode()
            output.put("type", "function_call_output")
            output.put("call_id", tool.callId)
            output.put(
                "output",
                "Error: tool call had empty arguments. Retry the same tool with complete JSON arguments.",
            )
            input.add(output)
        }
        retry.set("input", input)
        return retry
    }

    fun proxyAsChatCompletionJson(
        request: JsonNode,
        exchangeId: Long = 0,
    ): String {
        val chunks =
            proxyAsChatCompletionsSse(request, exchangeId)
                .mapNotNull { frame ->
                    val data = frame.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") return@mapNotNull null
                    runCatching { objectMapper.readTree(data) }.getOrNull()
                }
                .collectList()
                .block()
                .orEmpty()

        val content = StringBuilder()
        var finishReason = "stop"
        var id = "chatcmpl-adapter"
        var model = ModelResolver.DEFAULT
        var usage: JsonNode? = null
        for (chunk in chunks) {
            if (chunk.has("id")) id = JsonNodes.textAt(chunk, "id", id)
            if (chunk.has("model")) model = JsonNodes.textAt(chunk, "model", model)
            if (chunk.has("usage") && !chunk.path("usage").isNull) usage = chunk.path("usage")
            val choice = chunk.path("choices").get(0) ?: continue
            val delta = choice.path("delta").path("content")
            if (!delta.isNull && delta.isValueNode) content.append(JsonNodes.text(delta))
            val reason = choice.path("finish_reason")
            val reasonText = JsonNodes.text(reason)
            if (reasonText.isNotBlank()) {
                finishReason = reasonText
            }
        }

        val root = objectMapper.createObjectNode()
        root.put("id", id)
        root.put("object", "chat.completion")
        root.put("created", System.currentTimeMillis() / 1000)
        root.put("model", model)
        val choices = objectMapper.createArrayNode()
        val choice = objectMapper.createObjectNode()
        choice.put("index", 0)
        val message = objectMapper.createObjectNode()
        message.put("role", "assistant")
        message.put("content", content.toString())
        choice.set("message", message)
        choice.put("finish_reason", finishReason)
        choices.add(choice)
        root.set("choices", choices)
        usage?.let { root.set("usage", it) }
        return objectMapper.writeValueAsString(root)
    }

    private fun logCodexTelemetry(payload: String) {
        val event = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
        val type = JsonNodes.textAt(event, "type")
        if (type == "response.failed" || type == "error") {
            log.warn("Codex stream error event: {}", payload.take(500))
        }
        if (type == "response.completed" || type == "response.incomplete" || type == "response.done") {
            logUsage(event)
        }
    }

    private fun logUsage(event: JsonNode) {
        val usage = event.path("response").path("usage").takeIf { !it.isMissingNode && !it.isNull }
            ?: event.path("usage").takeIf { !it.isMissingNode && !it.isNull }
            ?: return

        val inputTokens = firstInt(usage, "input_tokens", "inputTokens")
        val outputTokens = firstInt(usage, "output_tokens", "outputTokens")
        val totalTokens = firstInt(usage, "total_tokens", "totalTokens")
        val reasoningTokens =
            usage.path("output_tokens_details").takeIf { !it.isMissingNode && !it.isNull }?.let { details ->
                firstInt(details, "reasoning_tokens", "reasoningTokens")
            } ?: usage.path("reasoning_tokens").takeIf { !it.isMissingNode && !it.isNull }?.asInt()

        log.info(
            "Codex usage inputTokens={} outputTokens={} reasoningTokens={} totalTokens={}",
            inputTokens ?: -1,
            outputTokens ?: -1,
            reasoningTokens ?: -1,
            totalTokens ?: -1,
        )
    }

    private fun firstInt(node: JsonNode, vararg fields: String): Int? {
        for (field in fields) {
            val value = node.path(field)
            if (!value.isMissingNode && !value.isNull && value.canConvertToInt()) {
                return value.asInt()
            }
        }
        return null
    }

    private fun decodeSseEvents(
        buffers: Flux<org.springframework.core.io.buffer.DataBuffer>,
        codexRaw: StringBuilder,
    ): Flux<String> {
        return buffers
            .map { buffer ->
                val text = buffer.toString(StandardCharsets.UTF_8)
                DataBufferUtils.release(buffer)
                synchronized(codexRaw) { codexRaw.append(text) }
                text
            }
            .concatMapIterable { chunk -> chunk.split(Regex("(?<=\n)")) }
            .scan(SseAccumulator()) { acc, line -> acc.accept(line) }
            .filter { it.emit != null }
            .mapNotNull { it.emit }
    }

    private data class SseAccumulator(
        val buffer: StringBuilder = StringBuilder(),
        val emit: String? = null,
    ) {
        fun accept(line: String): SseAccumulator {
            if (line == "\n" || line == "\r\n") {
                val payload = extractData(buffer.toString())
                buffer.clear()
                return SseAccumulator(buffer, payload)
            }
            buffer.append(line)
            return SseAccumulator(buffer, null)
        }

        private fun extractData(block: String): String? {
            val dataLines =
                block.lineSequence()
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .filter { it.isNotEmpty() && it != "[DONE]" }
                    .toList()
            return if (dataLines.isEmpty()) null else dataLines.joinToString("\n")
        }
    }
}
