package com.opengpt.api

import com.opengpt.auth.AuthenticationRequiredException
import com.opengpt.codex.CodexBackendException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.io.IOException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AuthenticationRequiredException::class)
    fun authentication(error: AuthenticationRequiredException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            errorBody(error.message ?: "ChatGPT account authentication required", "authentication_error"),
        )

    @ExceptionHandler(CodexBackendException::class)
    fun backend(error: CodexBackendException): ResponseEntity<Map<String, Any>> {
        log.warn("Codex backend request failed status={} body={}", error.status, error.responseBody.take(300))
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .header("Content-Type", "application/json")
            .body(errorBody("Codex backend request failed", "server_error"))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun status(error: ResponseStatusException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(error.statusCode)
            .header("Content-Type", "application/json")
            .body(errorBody(error.reason ?: "request failed", "invalid_request_error"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun missingStatic(error: NoResourceFoundException): ResponseEntity<Void> = ResponseEntity.notFound().build()

    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun asyncClientGone(error: AsyncRequestNotUsableException): ResponseEntity<Void> {
        log.info("Client disconnected: {}", error.message)
        return ResponseEntity.noContent().build()
    }

    @ExceptionHandler(IOException::class)
    fun ioClientGone(error: IOException): ResponseEntity<Map<String, Any>>? {
        val message = error.message.orEmpty()
        if (
            message.contains("disconnected client", ignoreCase = true) ||
            message.contains("Broken pipe", ignoreCase = true)
        ) {
            log.info("Client disconnected: {}", message)
            return null
        }
        log.error("Unhandled IO error: {}", message)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .header("Content-Type", "application/json")
            .body(errorBody(message.ifBlank { "Internal server error" }, "server_error"))
    }

    @ExceptionHandler(Exception::class)
    fun generic(error: Exception): ResponseEntity<Map<String, Any>>? {
        val message = error.message.orEmpty()
        if (
            message.contains("disconnected client", ignoreCase = true) ||
            message.contains("Broken pipe", ignoreCase = true) ||
            error.javaClass.name.contains("AsyncRequestNotUsableException") ||
            error.javaClass.name.contains("ClientAbortException")
        ) {
            log.info("Client disconnected: {}", message)
            return null
        }
        log.error("Unhandled error: {}", message)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .header("Content-Type", "application/json")
            .body(errorBody(message.ifBlank { "Internal server error" }, "server_error"))
    }

    private fun errorBody(
        message: String,
        type: String,
    ): Map<String, Any> =
        mapOf(
            "error" to
                mapOf(
                    "message" to message,
                    "type" to type,
                ),
        )
}
