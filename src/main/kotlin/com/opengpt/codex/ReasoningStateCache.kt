package com.opengpt.codex

import com.opengpt.util.JsonNodes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat Completions clients (Cursor) do not round-trip Responses reasoning items.
 * With Codex `store:false`, later turns need `reasoning.encrypted_content` from the
 * prior assistant turn (same as OpenCode).
 */
@Component
class ReasoningStateCache {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byCallId = ConcurrentHashMap<String, Entry>()
    private val byTextHash = ConcurrentHashMap<String, Entry>()

    fun rememberTurn(
        callIds: Collection<String>,
        assistantText: String?,
        reasoningItems: List<ObjectNode>,
    ) {
        val usable =
            reasoningItems.filter { item ->
                JsonNodes.textAt(item, "type") == "reasoning" &&
                    JsonNodes.textAt(item, "encrypted_content").isNotBlank()
            }
        if (usable.isEmpty()) return

        val snapshot = usable.map { it.deepCopy() as ObjectNode }
        val expiresAt = System.currentTimeMillis() + TTL_MS
        var stored = 0
        for (callId in callIds) {
            if (callId.isBlank()) continue
            byCallId[callId] = Entry(snapshot, expiresAt)
            stored++
        }
        val text = assistantText?.trim().orEmpty()
        if (text.isNotEmpty()) {
            byTextHash[hashText(text)] = Entry(snapshot, expiresAt)
            stored++
        }
        if (stored == 0) return
        log.info(
            "Cached {} reasoning item(s) callIds={} textHash={}",
            snapshot.size,
            callIds.count { it.isNotBlank() },
            text.isNotEmpty(),
        )
        prune()
    }

    fun takeForCallIds(callIds: Collection<String>): List<ObjectNode> {
        prune()
        for (callId in callIds) {
            if (callId.isBlank()) continue
            lookup(byCallId, callId)?.let { return it }
        }
        return emptyList()
    }

    fun takeForAssistantText(assistantText: String): List<ObjectNode> {
        prune()
        val text = assistantText.trim()
        if (text.isEmpty()) return emptyList()
        return lookup(byTextHash, hashText(text)).orEmpty()
    }

    private fun lookup(
        map: ConcurrentHashMap<String, Entry>,
        key: String,
    ): List<ObjectNode>? {
        val entry = map[key] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            map.remove(key)
            return null
        }
        return entry.items.map { it.deepCopy() as ObjectNode }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        byCallId.entries.removeIf { it.value.expiresAt < now }
        byTextHash.entries.removeIf { it.value.expiresAt < now }
    }

    private fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class Entry(
        val items: List<ObjectNode>,
        val expiresAt: Long,
    )

    companion object {
        private const val TTL_MS = 2 * 60 * 60 * 1000L
    }
}
