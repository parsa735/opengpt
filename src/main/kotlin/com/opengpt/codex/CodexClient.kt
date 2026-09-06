package com.opengpt.codex

import com.opengpt.auth.TokenRefreshService
import com.opengpt.config.CodexProperties
import com.opengpt.util.JsonNodes
import tools.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux

@Component
class CodexClient(
    private val properties: CodexProperties,
    private val tokenRefreshService: TokenRefreshService,
    webClientBuilder: WebClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webClient = webClientBuilder.build()

    fun streamResponse(request: JsonNode): Flux<DataBuffer> {
        val token = tokenRefreshService.getValidToken()
        val model = JsonNodes.textAt(request, "model", "unknown")
        val reasoningEffort = extractReasoningEffort(request)
        log.info("Codex request started model={} reasoningEffort={}", model, reasoningEffort)

        val headers =
            HttpHeaders().apply {
                setBearerAuth(token.accessToken)
                set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                set(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                set("User-Agent", properties.userAgent)
                set("originator", properties.originator)
                token.accountId?.let { set("ChatGPT-Account-Id", it) }
                token.residency?.let { set("x-openai-internal-codex-residency", it) }
            }

        return webClient
            .post()
            .uri(properties.endpoint)
            .headers { it.addAll(headers) }
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }) { response ->
                response.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                    CodexBackendException(response.statusCode().value(), body)
                }
            }
            .bodyToFlux(DataBuffer::class.java)
            .doOnComplete { log.info("Stream completed model={} reasoningEffort={}", model, reasoningEffort) }
    }

    private fun extractReasoningEffort(request: JsonNode): String {
        val fromObject = request.path("reasoning").path("effort")
        val fromObjectText = JsonNodes.text(fromObject)
        if (fromObjectText.isNotBlank()) return fromObjectText
        val flat =
            sequenceOf("reasoning_effort", "reasoningEffort")
                .map { JsonNodes.textAt(request, it) }
                .firstOrNull { it.isNotBlank() }
        return flat ?: "none"
    }
}

class CodexBackendException(
    val status: Int,
    val responseBody: String,
) : RuntimeException("Codex backend request failed: HTTP $status")
