package com.opengpt.api

import com.opengpt.debug.TrafficDebugLog
import com.opengpt.streaming.SseProxy
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

@RestController
@RequestMapping("/v1")
class ResponsesController(
    private val sseProxy: SseProxy,
    private val trafficDebugLog: TrafficDebugLog,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/responses")
    fun responses(
        @RequestBody body: JsonNode,
        response: HttpServletResponse,
    ) {
        val exchangeId = trafficDebugLog.nextExchangeId()
        trafficDebugLog.write(exchangeId, "CURSOR_REQUEST /v1/responses", objectMapper.writeValueAsString(body))

        response.status = 200
        response.contentType = "text/event-stream;charset=UTF-8"
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("X-Accel-Buffering", "no")

        val out = response.outputStream
        val cursorOut = StringBuilder()
        try {
            sseProxy
                .proxyResponsesSse(body, exchangeId)
                .doOnNext { frame ->
                    cursorOut.append(frame)
                    out.write(frame.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                .blockLast()
            trafficDebugLog.write(exchangeId, "CURSOR_RESPONSE_SSE", cursorOut.toString())
        } catch (error: Exception) {
            trafficDebugLog.write(exchangeId, "CURSOR_RESPONSE_ERROR", error.stackTraceToString())
            if (error.message?.contains("disconnected client", ignoreCase = true) == true ||
                error.javaClass.name.contains("ClientAbortException") ||
                error.javaClass.name.contains("AsyncRequestNotUsableException")
            ) {
                log.info("Client disconnected during responses stream")
                return
            }
            throw error
        }
    }
}
