package com.opengpt.debug

import com.opengpt.config.AdapterProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Lab-stage plain-text traffic dump for Cursor ↔ adapter ↔ Codex.
 * One append-only file, no fancy formatting.
 */
@Component
class TrafficDebugLog(
    properties: AdapterProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val path: Path = Path.of(properties.trafficLogPath)
    private val lock = Any()
    private val exchangeSeq = AtomicLong()
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @PostConstruct
    fun init() {
        Files.createDirectories(path.parent)
        writeRaw(
            "\n########## adapter start ${now()} path=$path ##########\n",
        )
        log.info("Traffic debug log: {}", path.toAbsolutePath())
    }

    fun nextExchangeId(): Long = exchangeSeq.incrementAndGet()

    fun write(
        exchangeId: Long,
        label: String,
        body: String,
    ) {
        val text =
            buildString {
                append("----- ")
                append(now())
                append(" exchange=")
                append(exchangeId)
                append(' ')
                append(label)
                append(" -----\n")
                append(body)
                if (!body.endsWith("\n")) append('\n')
                append('\n')
            }
        writeRaw(text)
    }

    private fun now(): String = LocalDateTime.now().format(timeFormat)

    private fun writeRaw(text: String) {
        synchronized(lock) {
            Files.writeString(
                path,
                text,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
        }
    }
}
