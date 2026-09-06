package com.opengpt.gateway

import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RequestTypeDetectorTest {
    private val detector = RequestTypeDetector()
    private val mapper = ObjectMapper()

    @Test
    fun `detects responses format`() {
        val node = mapper.readTree("""{"input":[]}""")
        assertEquals(RequestType.RESPONSES, detector.detect(node))
    }

    @Test
    fun `detects chat completions format`() {
        val node = mapper.readTree("""{"messages":[]}""")
        assertEquals(RequestType.CHAT_COMPLETIONS, detector.detect(node))
    }

    @Test
    fun `prefers chat completions when both present`() {
        val node = mapper.readTree("""{"input":[],"messages":[]}""")
        assertEquals(RequestType.CHAT_COMPLETIONS, detector.detect(node))
    }
}
