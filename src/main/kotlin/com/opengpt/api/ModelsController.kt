package com.opengpt.api

import com.opengpt.codex.ModelResolver
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class ModelsController {
    @GetMapping("/models")
    fun models(): Map<String, Any> =
        mapOf(
            "object" to "list",
            "data" to
                ModelResolver.PUBLIC_MODELS.map { id ->
                    mapOf(
                        "id" to id,
                        "object" to "model",
                        "owned_by" to "openai",
                    )
                },
        )
}
