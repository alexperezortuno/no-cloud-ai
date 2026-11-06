package dev.nocloud.ai.chat

import java.awt.Color

/**
 * The one code palette used by the chat.
 *
 * The colors are fixed instead of following the IDE theme: Swing's HTML renderer
 * resolves `color: inherit` to black and drops `background-color: transparent`,
 * so a themed or inherited declaration can end up as dark text on a dark
 * background. Every code element therefore carries the explicit colors below and
 * inline code and code blocks share them, which keeps the contrast readable in
 * every theme.
 */
object ChatCodeStyle {

    /** Background of inline code and of code blocks. */
    val background: Color = Color(0x1E1E1E)

    /** Text color of inline code and of code blocks. */
    val foreground: Color = Color(0xD4D4D4)

    /** Border color of code blocks. */
    val border: Color = Color(0x3C3C3C)

    val backgroundHex: String = background.hex()
    val foregroundHex: String = foreground.hex()
    val borderHex: String = border.hex()

    private const val MONOSPACE = "'Monospaced', 'Consolas', 'Menlo', monospace"

    /** The CSS rules that paint every kind of code with [background] and [foreground]. */
    fun css(): String = """
        code {
            background-color: $backgroundHex;
            color: $foregroundHex;
            padding: 2px 6px;
            font-family: $MONOSPACE;
            font-size: 12px;
        }
        pre {
            background-color: $backgroundHex;
            color: $foregroundHex;
            padding: 12px;
            margin: 8px 0;
            font-family: $MONOSPACE;
            font-size: 12px;
            border: 1px solid $borderHex;
        }
        pre code {
            background-color: $backgroundHex;
            color: $foregroundHex;
            padding: 0;
        }
    """.trimIndent()

    private fun Color.hex(): String = String.format("#%02X%02X%02X", red, green, blue)
}
