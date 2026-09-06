package com.opengpt.api

import com.opengpt.codex.CodexRequestMapper
import com.opengpt.debug.TrafficDebugLog
import com.opengpt.gateway.RequestType
import com.opengpt.gateway.RequestTypeDetector
import com.opengpt.streaming.SseProxy
import com.opengpt.util.JsonNodes
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

@RestController
@RequestMapping("/v1")
class ChatCompletionsController(
    private val detector: RequestTypeDetector,
    private val requestMapper: CodexRequestMapper,
    private val sseProxy: SseProxy,
    private val trafficDebugLog: TrafficDebugLog,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/chat/completions")
    fun chatCompletions(
        @RequestBody body: JsonNode,
        response: HttpServletResponse,
    ) {
        val exchangeId = trafficDebugLog.nextExchangeId()
        trafficDebugLog.write(exchangeId, "CURSOR_REQUEST /v1/chat/completions", objectMapper.writeValueAsString(body))

        val messageCount = body.path("messages").takeIf { it.isArray }?.size() ?: 0
        val toolCount = body.path("tools").takeIf { it.isArray }?.size() ?: 0
        val toolResultCount =
            body.path("messages").takeIf { it.isArray }?.count { JsonNodes.textAt(it, "role") == "tool" } ?: 0
        log.info(
            "Incoming chat/completions exchange={} messages={} tools={} toolResults={} stream={}",
            exchangeId,
            messageCount,
            toolCount,
            toolResultCount,
            !body.has("stream") || body.path("stream").asBoolean(true),
        )

        val responsesBody =
            when (detector.detect(body)) {
                RequestType.RESPONSES -> body
                RequestType.CHAT_COMPLETIONS -> requestMapper.toResponsesRequest(body)
            }

        val stream = !body.has("stream") || body.path("stream").asBoolean(true)
        if (!stream) {
            val json = sseProxy.proxyAsChatCompletionJson(responsesBody, exchangeId)
            trafficDebugLog.write(exchangeId, "CURSOR_RESPONSE_JSON", json)
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = StandardCharsets.UTF_8.name()
            response.outputStream.write(json.toByteArray(StandardCharsets.UTF_8))
            response.outputStream.flush()
            return
        }

        response.status = 200
        response.contentType = "text/event-stream;charset=UTF-8"
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Content-Encoding", "identity")

        val out = response.outputStream
        val cursorOut = StringBuilder()
        try {
            sseProxy
                .proxyAsChatCompletionsSse(responsesBody, exchangeId)
                .doOnNext { frame ->
                    cursorOut.append(frame)
                    out.write(frame.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                .blockLast()
            trafficDebugLog.write(exchangeId, "CURSOR_RESPONSE_SSE", cursorOut.toString())
        } catch (error: Exception) {
            trafficDebugLog.write(
                exchangeId,
                "CURSOR_RESPONSE_ERROR",
                error.stackTraceToString(),
            )
            if (isClientDisconnect(error)) {
                log.info("Client disconnected during chat completions stream exchange={}", exchangeId)
                return
            }
            throw error
        }
    }

    private fun isClientDisconnect(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val name = current.javaClass.name
            val message = current.message.orEmpty()
            if (
                name.contains("AsyncRequestNotUsableException") ||
                name.contains("ClientAbortException") ||
                message.contains("Broken pipe", ignoreCase = true) ||
                message.contains("disconnected client", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
