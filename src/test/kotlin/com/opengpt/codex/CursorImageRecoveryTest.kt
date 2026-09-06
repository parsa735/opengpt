package com.opengpt.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

class CursorImageRecoveryTest {
    private val recovery = CursorImageRecovery()
    private val mapper = ObjectMapper()

    @TempDir
    lateinit var temp: Path

    @Test
    fun `recovers original screenshot when Cursor sends black placeholder`() {
        val screenshots = temp.resolve("Pictures").resolve("Screenshots")
        Files.createDirectories(screenshots)
        val original = screenshots.resolve("Screenshot from 2026-02-23 10-39-34.png")
        writeColoredPng(original, Color.WHITE)

        val assetDir = temp.resolve("assets")
        Files.createDirectories(assetDir)
        val asset =
            assetDir.resolve(
                "Screenshot_from_2026-02-23_10-39-34-4f8a08a1-4981-4758-a686-a8113b04be02.png",
            )
        writeBlackJpeg(asset)

        // Point recovery search at our temp home by using asset path parent chain:
        // findOriginalNear searches ~/Pictures/Screenshots — override by placing candidate
        // next to the asset after name rewrite, and also copy into a discoverable path.
        // We invoke findOriginalNear directly for the naming, then resolveDataUrl with
        // a custom home is hard; instead put the original beside rewritten name in assetDir
        // AND in the real search by temporarily relying on findOriginalNear's asset parent.
        val renamed = assetDir.resolve("Screenshot from 2026-02-23 10-39-34.png")
        Files.copy(original, renamed)

        val blackUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(asset))
        val imageNode =
            mapper.readTree(
                """{"url":"$blackUrl","dimensions":{"width":0,"height":0}}""",
            )
        val resolved =
            recovery.resolveDataUrl(
                dataUrl = blackUrl,
                imageNode = imageNode,
                diskPaths = listOf(asset.toString()),
                imageIndex = 0,
            )
        assertTrue(resolved.dataUrl.startsWith("data:image/"))
        assertTrue(resolved.source.contains("Screenshot from 2026-02-23 10-39-34"))
        val recoveredBytes = Base64.getDecoder().decode(resolved.dataUrl.substringAfter("base64,"))
        assertTrue(recoveredBytes.size > Files.readAllBytes(asset).size)
    }

    @Test
    fun `extracts image_files paths from content`() {
        val content =
            mapper.readTree(
                """
                [
                  {"type":"text","text":"hello"},
                  {
                    "type":"text",
                    "text":"<image_files>\n1. /tmp/a.png\n\nThese files can be read\n</image_files>"
                  }
                ]
                """.trimIndent(),
            )
        assertEquals(listOf("/tmp/a.png"), recovery.extractDiskPaths(content))
    }

    private fun writeBlackJpeg(path: Path) {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, 64, 64)
        g.dispose()
        ImageIO.write(image, "jpg", path.toFile())
    }

    private fun writeColoredPng(
        path: Path,
        color: Color,
    ) {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, 128, 128)
        g.color = Color.BLACK
        g.drawString("hello", 20, 40)
        g.dispose()
        ImageIO.write(image, "png", path.toFile())
    }
}
