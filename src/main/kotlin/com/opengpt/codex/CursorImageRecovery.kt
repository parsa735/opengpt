package com.opengpt.codex

import com.opengpt.util.JsonNodes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Cursor's custom OpenAI-compatible vision path often replaces real attachments with a
 * solid-black JPEG placeholder (`dimensions: {0,0}`, ~4KB) while still mentioning the
 * saved asset path under `<image_files>`. Recover the original when possible.
 */
@Component
class CursorImageRecovery {
    private val log = LoggerFactory.getLogger(javaClass)
    private val uuidSuffix =
        Regex("-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=\\.[^.]+$)")
    private val screenshotFrom =
        Regex("""^(Screenshot)_from_(\d{4}-\d{2}-\d{2})_(\d{2}-\d{2}-\d{2})(\.[^.]+)$""", RegexOption.IGNORE_CASE)
    private val imageFilesLine = Regex("""(?m)^\d+\.\s+(\S+)""")

    data class ResolvedImage(
        val dataUrl: String,
        val source: String,
    )

    fun extractDiskPaths(content: JsonNode): List<String> {
        if (!content.isArray) return emptyList()
        val paths = mutableListOf<String>()
        for (part in content) {
            val text =
                when {
                    part.isTextual -> JsonNodes.text(part)
                    JsonNodes.textAt(part, "type") == "text" || part.has("text") -> JsonNodes.textAt(part, "text")
                    else -> ""
                }
            if (text.isEmpty() || !text.contains("<image_files>")) continue
            for (match in imageFilesLine.findAll(text)) {
                paths += match.groupValues[1]
            }
        }
        return paths
    }

    fun resolveDataUrl(
        dataUrl: String,
        imageNode: JsonNode,
        diskPaths: List<String>,
        imageIndex: Int,
    ): ResolvedImage {
        val hintedPath = diskPaths.getOrNull(imageIndex)
        val placeholder = isPlaceholder(dataUrl, imageNode)
        if (!placeholder && !looksBrokenDiskHint(hintedPath)) {
            return ResolvedImage(dataUrl = dataUrl, source = "cursor-payload")
        }

        val candidates = mutableListOf<Path>()
        if (!hintedPath.isNullOrBlank()) {
            val hinted = Path.of(hintedPath)
            candidates.add(hinted)
            findOriginalNear(hinted)?.let { candidates.add(it) }
        }
        for (path in diskPaths) {
            val p = Path.of(path)
            candidates.add(p)
            findOriginalNear(p)?.let { candidates.add(it) }
        }

        for (candidate in candidates.distinct()) {
            if (!candidate.isRegularFile()) continue
            val bytes =
                runCatching { Files.readAllBytes(candidate) }.getOrNull()
                    ?: continue
            if (bytes.size < 64 || isUniformDarkImage(bytes)) continue
            val mime = mimeFor(candidate, bytes)
            val encoded = Base64.getEncoder().encodeToString(bytes)
            val recovered = "data:$mime;base64,$encoded"
            log.info(
                "Recovered Cursor placeholder image imageIndex={} from {} ({} bytes, wasPlaceholder={})",
                imageIndex,
                candidate,
                bytes.size,
                placeholder,
            )
            return ResolvedImage(dataUrl = recovered, source = candidate.toString())
        }

        if (placeholder) {
            log.warn(
                "Cursor image payload looks like a black placeholder (detail/dimensions/path={}/{}) and no original was found",
                JsonNodes.textAt(imageNode, "detail"),
                hintedPath,
            )
        }
        return ResolvedImage(dataUrl = dataUrl, source = "cursor-payload")
    }

    private fun looksBrokenDiskHint(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = Path.of(path)
        if (!file.isRegularFile()) return true
        val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return true
        return bytes.size < 8_000 || isUniformDarkImage(bytes)
    }

    private fun isPlaceholder(
        dataUrl: String,
        imageNode: JsonNode,
    ): Boolean {
        val width = intField(imageNode, "dimensions", "width")
        val height = intField(imageNode, "dimensions", "height")
        if (width == 0 || height == 0) return true
        if (!dataUrl.startsWith("data:") || !dataUrl.contains("base64,")) return false
        val raw =
            runCatching {
                Base64.getDecoder().decode(dataUrl.substringAfter("base64,"))
            }.getOrNull() ?: return false
        if (raw.size < 8_000) return true
        return isUniformDarkImage(raw)
    }

    private fun intField(
        node: JsonNode,
        obj: String,
        field: String,
    ): Int? {
        val dims = node.path(obj)
        if (!dims.isObject || !dims.has(field)) return null
        return dims.path(field).asInt(-1).takeIf { it >= 0 }
    }

    private fun isUniformDarkImage(bytes: ByteArray): Boolean {
        val image =
            runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
                ?: return false
        return isUniformDark(image)
    }

    private fun isUniformDark(image: BufferedImage): Boolean {
        val stepX = (image.width / 32).coerceAtLeast(1)
        val stepY = (image.height / 32).coerceAtLeast(1)
        var samples = 0
        var dark = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xff
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff
                val lum = (r + g + b) / 3
                samples += 1
                if (lum <= 16) dark += 1
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return true
        return dark.toDouble() / samples.toDouble() >= 0.98
    }

    fun findOriginalNear(cursorAsset: Path): Path? {
        val stripped = uuidSuffix.replace(cursorAsset.name, "")
        val names = linkedSetOf<String>()
        val screenshot = screenshotFrom.matchEntire(stripped)
        if (screenshot != null) {
            names += "Screenshot from ${screenshot.groupValues[2]} ${screenshot.groupValues[3]}${screenshot.groupValues[4]}"
            names += "Screenshot from ${screenshot.groupValues[2]} ${screenshot.groupValues[3]}.png"
            names += "Screenshot from ${screenshot.groupValues[2]} ${screenshot.groupValues[3]}.jpg"
        }
        names += stripped.replace('_', ' ')
        names += stripped

        val home = Path.of(System.getProperty("user.home"))
        val dirs =
            listOfNotNull(
                cursorAsset.parent,
                home.resolve("Pictures/Screenshots"),
                home.resolve("Pictures"),
                home.resolve("Downloads"),
                home.resolve("Desktop"),
            )
        for (dir in dirs) {
            if (!dir.isDirectory()) continue
            for (name in names) {
                val candidate = dir.resolve(name)
                if (candidate.isRegularFile() && Files.size(candidate) >= 8_000) return candidate
            }
        }
        return null
    }

    private fun mimeFor(
        path: Path,
        bytes: ByteArray,
    ): String {
        if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) return "image/jpeg"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "image/png"
        return when (path.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
    }
}
