package com.opengpt.codex

import java.security.MessageDigest

/**
 * Codex rejects `call_id` longer than 64 characters.
 * Cursor sometimes echoes / generates longer ids; hash them deterministically
 * so `function_call` and `function_call_output` stay paired.
 */
object CallIdNormalizer {
    const val MAX_LENGTH = 64

    fun normalize(raw: String): String {
        val id = raw.trim()
        if (id.isEmpty()) return id
        if (id.length <= MAX_LENGTH) return id

        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        // "call_" + 58 hex chars = 63, safely under Codex limit.
        return "call_" + digest.take(58)
    }
}
