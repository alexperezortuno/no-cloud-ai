package dev.nocloud.ai.chat

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet

/** Converts Markdown to the small HTML subset the Swing text pane understands. */
object Markdown {

    private val options: MutableDataSet = MutableDataSet().apply {
        set(Parser.EXTENSIONS, listOf(com.vladsch.flexmark.ext.tables.TablesExtension.create()))
        set(HtmlRenderer.SOFT_BREAK, "<br/>")
    }

    private val parser: Parser = Parser.builder(options).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

    fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        return runCatching { renderer.render(parser.parse(markdown)) }.getOrElse {
            markdown.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br/>")
        }
    }
}
