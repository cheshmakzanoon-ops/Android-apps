package com.agency.photoduel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The Compose palette ([PdTokens]) and res/values/colors.xml describe the same tokens. This test reads
 * both and fails on any difference, so the window background and the Compose theme never drift apart.
 */
class PaletteTokensTest {

    private fun colorsXml(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            listOf("src/main/res/values/colors.xml", "apps/photoduel/src/main/res/values/colors.xml").forEach { path ->
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("colors.xml not found above ${System.getProperty("user.dir")}")
    }

    private fun xmlColors(): Map<String, Int> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(colorsXml())
        val nodes = document.getElementsByTagName("color")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            val hex = element.textContent.trim().removePrefix("#")
            val argb = if (hex.length == 6) "FF$hex" else hex
            element.getAttribute("name") to argb.toLong(16).toInt()
        }
    }

    /** camelCase field name to the snake_case token name used in XML and Figma. */
    private fun snake(name: String): String = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    /** Reads every Color field of [palette]. Color is a value class, so each field is stored as a long. */
    private fun paletteColors(palette: PdPalette): Map<String, Int> =
        PdPalette::class.java.declaredFields
            .filter { it.type == java.lang.Long.TYPE }
            .associate { field ->
                field.isAccessible = true
                snake(field.name) to Color(field.getLong(palette).toULong()).toArgb()
            }

    @Test
    fun lightAndDarkTokensMatchColorsXml() {
        val xml = xmlColors()
        for ((suffix, palette) in listOf("light" to PdTokens.Light, "dark" to PdTokens.Dark)) {
            val tokens = paletteColors(palette)
            assertEquals("token count", 21, tokens.size)
            for ((name, argb) in tokens) {
                val key = "pd_${name}_$suffix"
                assertTrue("$key is missing from colors.xml", key in xml)
                assertEquals("$key differs", Integer.toHexString(xml.getValue(key)), Integer.toHexString(argb))
            }
        }
    }
}
