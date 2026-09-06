package com.opengpt.codex

import com.opengpt.util.JsonNodes
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * Extracts reasoning effort from the request shapes accepted by the adapter.
 *
 * Cursor's custom Base URL path currently drops its UI reasoning selector. It
 * also canonicalizes GPT-looking effort aliases before sending them, so
 * Cursor-safe `cla-{sol|terra|luna}-{effort}` aliases carry the choice reliably.
 * `cursor_model_params` support is retained for compatible Cursor SDK/proxy clients.
 */
object ReasoningEffortResolver {
    private val cursorParameterIds = setOf("thinking_effort", "reasoning_effort", "reasoningeffort")

    fun fromRequest(request: JsonNode): String? {
        val reasoning = request.path("reasoning")
        if (reasoning.isObject) {
            normalize(JsonNodes.textAt(reasoning, "effort"))?.let { return it }
        }

        val cursorParameters = request.path("cursor_model_params")
        if (cursorParameters.isArray) {
            for (parameter in cursorParameters) {
                val id = JsonNodes.textAt(parameter, "id").trim().lowercase().replace("-", "_")
                if (id in cursorParameterIds) {
                    normalize(JsonNodes.textAt(parameter, "value"))?.let { return it }
                }
            }
        }

        for (field in listOf("reasoning_effort", "reasoningEffort")) {
            normalize(JsonNodes.textAt(request, field))?.let { return it }
        }
        return null
    }

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return null
        return when (value.replace("-", "_").replace(" ", "_")) {
            "extra_high", "extrahigh", "max" -> "xhigh"
            else -> value
        }
    }
}

/**
 * Aligns with OpenCode Codex OAuth model filtering in
 * `packages/opencode/src/plugin/openai/codex.ts`:
 * - allowlisted ids pass through
 * - any `gpt-{major.minor}...` with minor > 5.4 passes through (e.g. gpt-5.6-sol)
 * - plain `gpt-5.6` and `*-pro` / reasoningMode=pro are not remapped here; clients should not send them
 *
 * Never remaps gpt-5.6-sol → gpt-5.5. OpenCode sends the api id as-is to Codex.
 */
@Component
class ModelResolver {
    data class Resolved(
        val model: String,
        val reasoningEffort: String?,
    )

    fun resolve(requested: String?): Resolved {
        val raw = requested?.trim().orEmpty()
        if (raw.isEmpty()) return Resolved(DEFAULT, null)

        val key = raw.lowercase()

        // Cursor canonicalizes GPT-looking aliases such as gpt-5.6-sol-low to
        // gpt-5.6-sol. These deliberately non-GPT aliases survive that boundary.
        resolveCursorEffortAlias(key)?.let { return it }

        // Other clients can carry effort as a normal model-id suffix.
        val stripped = stripEffortSuffix(key)
        if (stripped != null && isCodexCompatible(stripped.model)) return stripped

        if (isCodexCompatible(key)) return Resolved(key, null)

        return Resolved(key, null)
    }

    fun resolveModelId(requested: String?): String = resolve(requested).model

    private fun resolveCursorEffortAlias(modelId: String): Resolved? {
        for ((aliasBase, codexModel) in CURSOR_EFFORT_ALIAS_BASES) {
            for (effort in PUBLIC_EFFORTS) {
                if (modelId == "$aliasBase-$effort") {
                    return Resolved(codexModel, effort)
                }
            }
        }
        return null
    }

    fun isCodexCompatible(modelId: String): Boolean {
        if (BASE_ALLOWED.contains(modelId)) return true
        if (modelId == "gpt-5.6") return false
        if (modelId.endsWith("-pro") || modelId.contains("-pro-")) return false
        val match = GPT_VERSION.find(modelId) ?: return false
        return match.groupValues[1].toDouble() > 5.4
    }

    private fun stripEffortSuffix(modelId: String): Resolved? {
        for (effort in EFFORTS) {
            val suffix = "-$effort"
            if (modelId.endsWith(suffix)) {
                val base = modelId.removeSuffix(suffix)
                return Resolved(base, ReasoningEffortResolver.normalize(effort))
            }
        }
        return null
    }

    companion object {
        const val DEFAULT = "gpt-5.4"

        private val GPT_VERSION = Regex("""^gpt-(\d+\.\d+)""")

        private val EFFORT_ALIAS_BASES =
            listOf(
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
            )

        private val CURSOR_EFFORT_ALIAS_BASES =
            linkedMapOf(
                "cla-sol" to "gpt-5.6-sol",
                "cla-terra" to "gpt-5.6-terra",
                "cla-luna" to "gpt-5.6-luna",
            )

        private val PUBLIC_EFFORTS = listOf("low", "medium", "high", "xhigh")

        val BASE_ALLOWED =
            setOf(
                "gpt-5.4",
                "gpt-5.4-mini",
                "gpt-5.5",
                "gpt-5.3-codex-spark",
                "gpt-5",
                "gpt-5-codex",
            )

        /** Models advertised on /v1/models (OpenCode Codex OAuth surface). */
        val PUBLIC_MODELS =
            buildList {
                add("gpt-5.4")
                add("gpt-5.4-mini")
                add("gpt-5.5")
                addAll(EFFORT_ALIAS_BASES)
                add("gpt-5.3-codex-spark")
                EFFORT_ALIAS_BASES.forEach { model ->
                    PUBLIC_EFFORTS.forEach { effort ->
                        add("$model-$effort")
                    }
                }
                CURSOR_EFFORT_ALIAS_BASES.keys.forEach { alias ->
                    PUBLIC_EFFORTS.forEach { effort ->
                        add("$alias-$effort")
                    }
                }
            }

        private val EFFORTS = listOf("xhigh", "high", "medium", "low", "minimal", "none", "max")

        fun defaultEffortFor(model: String): String {
            val id = model.lowercase()
            if (id.contains("5.6") || id.contains("sol") || id.contains("terra") || id.contains("luna")) {
                return "xhigh"
            }
            if (id.contains("5.5") || id.contains("5.4")) return "high"
            return "medium"
        }
    }
}
