package dev.nocloud.ai.chat

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension
import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.html.Attributes
import com.vladsch.flexmark.html.renderer.*
import com.vladsch.flexmark.util.data.DataHolder

/** Converts Markdown to the small HTML subset the Swing text pane understands. */
object Markdown {

    private val options: MutableDataSet = MutableDataSet().apply {
        set(Parser.EXTENSIONS, listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create(),
            FootnoteExtension.create(),
            AnchorLinkExtension.create(),
            YamlFrontMatterExtension.create()
        ))
        set(HtmlRenderer.SOFT_BREAK, "<br/>")
    }

    private val parser: Parser = Parser.builder(options).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

    fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        return runCatching { renderer.render(parser.parse(markdown)) }.getOrElse {
            markdown.replace("&", "&").replace("<", "<").replace("\n", "<br/>")
        }
    }
}