package com.opengpt.util

import tools.jackson.databind.JsonNode

object JsonNodes {
    fun text(
        node: JsonNode,
        default: String = "",
    ): String {
        if (node.isMissingNode || node.isNull) return default
        if (node.isString) return node.stringValue() ?: default
        // Prefer default for objects/arrays; asString(default) already does this in Jackson 3.
        return node.asString(default)
    }

    fun textAt(
        node: JsonNode,
        field: String,
        default: String = "",
    ): String = text(node.path(field), default)

    /** Like [text], but serializes objects/arrays to JSON instead of dropping them. */
    fun jsonText(
        node: JsonNode,
        default: String = "",
    ): String {
        if (node.isMissingNode || node.isNull) return default
        if (node.isString) return node.stringValue() ?: default
        if (node.isObject || node.isArray) return node.toString()
        return node.asString(default)
    }

    fun jsonTextAt(
        node: JsonNode,
        field: String,
        default: String = "",
    ): String = jsonText(node.path(field), default)
}
