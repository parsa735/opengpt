package com.opengpt.auth

import com.opengpt.config.AdapterProperties
import com.opengpt.model.OAuthToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path

class FileTokenStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save load generates api key and clear`() {
        val path = tempDir.resolve("auth.json").toString()
        val store = FileTokenStore(AdapterProperties(path), jacksonObjectMapper())
        assertNull(store.getToken())

        store.save(
            OAuthToken(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAt = 123L,
                accountId = "acc",
            ),
        )
        val loaded = store.getToken()
        assertNotNull(loaded)
        assertEquals("access", loaded!!.accessToken)
        assertTrue(loaded.apiKey!!.startsWith("sk-cla-"))

        store.clear()
        assertNull(store.getToken())
    }
}
