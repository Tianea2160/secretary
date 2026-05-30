package org.tianea.secretary.telegram

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.springframework.stereotype.Component

/**
 * LLM의 GFM 마크다운을 Telegram MarkdownV2 방언으로 직렬화한다.
 *
 * Telegram MarkdownV2는 표·LaTeX·헤더를 지원하지 않고 `_*[]()~` 백틱 `>#+-=|{}.!\` 모두를 escape하라고 요구해서,
 * commonmark-java AST를 walk하며 Telegram이 받아들이는 형태로 재직렬화한다.
 */
@Component
class TelegramMarkdownRenderer : TelegramRenderer {
    private val parser: Parser =
        Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .build()

    /** 입력이 blank면 그대로 돌려준다. */
    override fun render(message: String): String {
        if (message.isBlank()) return message
        val doc = parser.parse(message)
        val visitor = TelegramVisitor()
        doc.accept(visitor)
        return visitor.result().trimEnd()
    }
}

private class TelegramVisitor : AbstractVisitor() {
    private val sb = StringBuilder()
    private var listDepth = 0

    fun result(): String = sb.toString()

    override fun visit(heading: Heading) {
        sb.append('*')
        visitChildren(heading)
        sb.append('*')
        sb.append("\n\n")
    }

    override fun visit(paragraph: Paragraph) {
        visitChildren(paragraph)
        sb.append("\n\n")
    }

    override fun visit(text: Text) {
        sb.append(escapeText(text.literal))
    }

    override fun visit(emphasis: Emphasis) {
        sb.append('_')
        visitChildren(emphasis)
        sb.append('_')
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        sb.append('*')
        visitChildren(strongEmphasis)
        sb.append('*')
    }

    override fun visit(code: Code) {
        sb.append('`')
        sb.append(escapeCode(code.literal))
        sb.append('`')
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        val info = fencedCodeBlock.info?.takeIf { it.isNotBlank() } ?: ""
        sb.append("```").append(info).append('\n')
        sb.append(escapeCode(fencedCodeBlock.literal))
        if (!fencedCodeBlock.literal.endsWith('\n')) sb.append('\n')
        sb.append("```\n\n")
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        sb.append("```\n")
        sb.append(escapeCode(indentedCodeBlock.literal))
        if (!indentedCodeBlock.literal.endsWith('\n')) sb.append('\n')
        sb.append("```\n\n")
    }

    override fun visit(link: Link) {
        sb.append('[')
        visitChildren(link)
        sb.append(']')
        sb.append('(').append(escapeLinkUrl(link.destination.orEmpty())).append(')')
    }

    override fun visit(image: Image) {
        sb.append('[')
        val alt = collectPlainText(image).ifEmpty { "image" }
        sb.append(escapeText(alt))
        sb.append(']')
        sb.append('(').append(escapeLinkUrl(image.destination.orEmpty())).append(')')
    }

    override fun visit(bulletList: BulletList) = renderList(bulletList)

    override fun visit(orderedList: OrderedList) = renderList(orderedList)

    private fun renderList(list: Node) {
        listDepth++
        var index = (list as? OrderedList)?.markerStartNumber ?: 1
        var child = list.firstChild
        while (child != null) {
            if (child is ListItem) {
                sb.append(indent())
                if (list is OrderedList) {
                    sb.append(index).append("\\. ")
                    index++
                } else {
                    sb.append("• ")
                }
                renderListItem(child)
                sb.append('\n')
            }
            child = child.next
        }
        listDepth--
        if (listDepth == 0) sb.append('\n')
    }

    private fun renderListItem(item: ListItem) {
        var child = item.firstChild
        var first = true
        while (child != null) {
            if (!first) sb.append('\n').append(indent()).append("  ")
            if (child is Paragraph) {
                visitChildren(child)
            } else {
                child.accept(this)
            }
            first = false
            child = child.next
        }
    }

    private fun indent(): String = "  ".repeat((listDepth - 1).coerceAtLeast(0))

    override fun visit(blockQuote: BlockQuote) {
        val nested = TelegramVisitor()
        var cur = blockQuote.firstChild
        while (cur != null) {
            cur.accept(nested)
            cur = cur.next
        }
        nested.result().trimEnd().lineSequence().forEach { line ->
            sb.append('>')
            if (line.isNotEmpty()) sb.append(' ').append(line)
            sb.append('\n')
        }
        sb.append('\n')
    }

    override fun visit(thematicBreak: ThematicBreak) {
        sb.append(escapeText("———")).append("\n\n")
    }

    override fun visit(softLineBreak: SoftLineBreak) {
        sb.append('\n')
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        sb.append('\n')
    }

    override fun visit(htmlBlock: HtmlBlock) {
        sb.append(escapeText(htmlBlock.literal)).append("\n\n")
    }

    override fun visit(htmlInline: HtmlInline) {
        sb.append(escapeText(htmlInline.literal))
    }

    override fun visit(customBlock: CustomBlock) {
        when (customBlock) {
            is TableBlock -> renderTable(customBlock)
            else -> visitChildren(customBlock)
        }
    }

    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is Strikethrough -> {
                sb.append('~')
                visitChildren(customNode)
                sb.append('~')
            }

            else -> {
                visitChildren(customNode)
            }
        }
    }

    private fun renderTable(table: TableBlock) {
        val rows = mutableListOf<List<String>>()
        var child = table.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> collectTableRows(child, rows)
                is TableBody -> collectTableRows(child, rows)
            }
            child = child.next
        }
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }
        val widths = IntArray(cols)
        for (row in rows) {
            for (i in row.indices) widths[i] = maxOf(widths[i], row[i].length)
        }
        sb.append("```\n")
        for ((rowIdx, row) in rows.withIndex()) {
            appendTableRow(row, widths, cols)
            if (rowIdx == 0) appendTableSeparator(widths, cols)
        }
        sb.append("```\n\n")
    }

    private fun appendTableRow(row: List<String>, widths: IntArray, cols: Int) {
        for (i in 0 until cols) {
            sb.append((row.getOrNull(i) ?: "").padEnd(widths[i]))
            if (i < cols - 1) sb.append(" | ")
        }
        sb.append('\n')
    }

    private fun appendTableSeparator(widths: IntArray, cols: Int) {
        for (i in 0 until cols) {
            sb.append("-".repeat(widths[i]))
            if (i < cols - 1) sb.append("-+-")
        }
        sb.append('\n')
    }

    private fun collectTableRows(parent: Node, rows: MutableList<List<String>>) {
        var row = parent.firstChild
        while (row != null) {
            if (row is TableRow) {
                val cells = mutableListOf<String>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) cells += collectPlainText(cell).trim()
                    cell = cell.next
                }
                rows += cells
            }
            row = row.next
        }
    }

    private fun collectPlainText(node: Node): String {
        val out = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> out.append(child.literal)
                is Code -> out.append(child.literal)
                is SoftLineBreak,
                is HardLineBreak -> out.append(' ')
                else -> out.append(collectPlainText(child))
            }
            child = child.next
        }
        return out.toString()
    }
}

private fun isReserved(c: Char): Boolean =
    when (c) {
        '_',
        '*',
        '[',
        ']',
        '(',
        ')',
        '~',
        '`',
        '>',
        '#',
        '+',
        '-',
        '=',
        '|',
        '{',
        '}',
        '.',
        '!',
        '\\' -> true

        else -> false
    }

private fun escapeText(s: String): String =
    buildString(s.length + 8) {
        for (c in s) {
            if (isReserved(c)) append('\\')
            append(c)
        }
    }

private fun escapeCode(s: String): String =
    buildString(s.length) {
        for (c in s) {
            if (c == '`' || c == '\\') append('\\')
            append(c)
        }
    }

private fun escapeLinkUrl(s: String): String =
    buildString(s.length) {
        for (c in s) {
            if (c == ')' || c == '\\') append('\\')
            append(c)
        }
    }
