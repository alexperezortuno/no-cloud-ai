package dev.nocloud.ai.chat

import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCodeStyleTest {

    @Test
    fun `code text keeps a readable contrast on the code background`() {
        val contrast = contrastRatio(ChatCodeStyle.foreground, ChatCodeStyle.background)

        assertTrue(contrast >= 4.5, "contrast of $contrast is below the WCAG AA ratio")
    }

    @Test
    fun `every kind of code carries the explicit colors`() {
        val css = ChatCodeStyle.css()

        listOf("code", "pre", "pre code").forEach { selector ->
            val rule = ruleFor(css, selector)
            assertTrue(
                rule.contains("background-color: ${ChatCodeStyle.backgroundHex}"),
                "$selector does not use the shared code background",
            )
            assertTrue(
                rule.contains("color: ${ChatCodeStyle.foregroundHex}"),
                "$selector does not use the shared code foreground",
            )
        }
    }

    @Test
    fun `no code rule relies on inherited or transparent colors`() {
        val css = ChatCodeStyle.css()

        assertFalse(css.contains("inherit"), "Swing resolves inherit to black")
        assertFalse(css.contains("transparent"), "Swing drops transparent backgrounds")
    }

    /** The declarations of the rule that starts with [selector]. */
    private fun ruleFor(css: String, selector: String): String {
        val block = css.split("}")
            .first { it.trim().startsWith("$selector {") }
        return block.substringAfter("{")
    }

    /** The WCAG contrast ratio between two opaque colors. */
    private fun contrastRatio(first: Color, second: Color): Double {
        val lighter = max(luminance(first), luminance(second))
        val darker = min(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Int): Double {
            val part = value / 255.0
            return if (part <= 0.03928) part / 12.92 else ((part + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
