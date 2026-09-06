package com.opengpt.gateway

import tools.jackson.databind.JsonNode
import org.springframework.stereotype.Component

enum class RequestType {
    RESPONSES,
    CHAT_COMPLETIONS,
}

@Component
class RequestTypeDetector {
    fun detect(request: JsonNode): RequestType {
        if (request.has("input") && !request.has("messages")) {
            return RequestType.RESPONSES
        }
        return RequestType.CHAT_COMPLETIONS
    }
}
