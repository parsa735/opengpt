package com.opengpt.auth

import com.opengpt.config.AdapterProperties
import com.opengpt.model.OAuthToken
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

interface TokenStore {
    fun getToken(): OAuthToken?

    fun save(token: OAuthToken)

    fun clear()

    fun isAuthenticated(): Boolean = getToken() != null

    fun getApiKey(): String? = getToken()?.apiKey

    fun requireApiKey(): String =
        getApiKey() ?: throw IllegalStateException("ChatGPT account authentication required")

    fun regenerateApiKey(): String {
        throw UnsupportedOperationException("API key regeneration is not supported by this store")
    }
}

@Component
class FileTokenStore(
    private val properties: AdapterProperties,
    private val objectMapper: ObjectMapper,
) : TokenStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantReadWriteLock()
    private val path: Path = Path.of(properties.storagePath).toAbsolutePath().normalize()

    override fun getToken(): OAuthToken? =
        lock.write {
            if (!Files.exists(path)) return null
            val loaded =
                runCatching {
                    objectMapper.readValue(Files.readString(path), OAuthToken::class.java)
                }.onFailure { log.warn("Failed to read auth storage") }
                    .getOrNull()
                    ?: return null
            if (!loaded.apiKey.isNullOrBlank()) return loaded
            val upgraded = loaded.copy(apiKey = ApiKeyGenerator.generate())
            writeUnlocked(upgraded)
            log.info("Generated local adapter API key for existing credentials")
            upgraded
        }

    override fun save(token: OAuthToken) {
        lock.write {
            val withKey =
                if (token.apiKey.isNullOrBlank()) {
                    token.copy(apiKey = ApiKeyGenerator.generate())
                } else {
                    token
                }
            writeUnlocked(withKey)
            log.info("OAuth credentials saved")
        }
    }

    override fun clear() {
        lock.write {
            Files.deleteIfExists(path)
            log.info("OAuth credentials cleared")
        }
    }

    override fun requireApiKey(): String =
        getApiKey() ?: throw IllegalStateException("ChatGPT account authentication required")

    override fun regenerateApiKey(): String {
        lock.write {
            val current = getTokenUnlocked() ?: throw IllegalStateException("Not authenticated")
            val next = current.copy(apiKey = ApiKeyGenerator.generate())
            writeUnlocked(next)
            log.info("Local adapter API key regenerated")
            return next.apiKey!!
        }
    }

    private fun getTokenUnlocked(): OAuthToken? {
        if (!Files.exists(path)) return null
        return runCatching {
            objectMapper.readValue(Files.readString(path), OAuthToken::class.java)
        }.getOrNull()
    }

    private fun writeUnlocked(token: OAuthToken) {
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(token),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
