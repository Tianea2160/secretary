package org.tianea.secretary.telegram.latex

/**
 * [MathNode] AST를 유니코드 평문으로 렌더링한다.
 *
 * 폴백 정책: 유니코드로 매핑 불가능한 노드는 [MathNode.toLatex]로 원본 LaTeX를 그대로 노출한다.
 * 위/아래 첨자는 all-or-nothing — 모든 글자가 유니코드 첨자로 매핑될 때만 변환하고, 한 글자라도
 * 실패하면 `_{...}`/`^{...}` 형태로 남긴다.
 */
internal object UnicodeMathRenderer {
    fun render(nodes: List<MathNode>): String = nodes.joinToString("") { render(it) }

    private fun render(node: MathNode): String =
        when (node) {
            is MathNode.Symbol -> node.text
            is MathNode.Group -> render(node.children)
            is MathNode.Command -> LatexSymbols.COMMAND_MAP[node.name] ?: node.toLatex()
            is MathNode.Fraction -> "(${render(node.numerator)})/(${render(node.denominator)})"
            is MathNode.Sqrt -> renderSqrt(node)
            is MathNode.Script -> renderScript(node)
            is MathNode.Accent -> renderAccent(node)
            is MathNode.FontWrapper -> renderFont(node)
            is MathNode.Delimited -> renderDelimited(node)
            is MathNode.Environment -> renderEnvironment(node)
            is MathNode.BigOperator -> renderBigOperator(node)
            is MathNode.Unknown -> node.toLatex()
        }

    private fun renderSqrt(node: MathNode.Sqrt): String {
        val radicand = render(node.radicand)
        val index = node.index?.let { render(it).trim() }
        if (index.isNullOrEmpty()) return "√($radicand)"
        val superscript = mapAllOrNull(index, LatexSymbols.SUPERSCRIPT_MAP)
        return if (superscript != null) "$superscript√($radicand)" else "√[$index]($radicand)"
    }

    private fun renderScript(node: MathNode.Script): String {
        val base = node.base?.let { render(it) } ?: ""
        val sub =
            node.sub?.let {
                val s = render(it)
                mapAllOrNull(s, LatexSymbols.SUBSCRIPT_MAP) ?: "_{$s}"
            } ?: ""
        val sup =
            node.sup?.let {
                val s = render(it)
                mapAllOrNull(s, LatexSymbols.SUPERSCRIPT_MAP) ?: "^{$s}"
            } ?: ""
        return base + sub + sup
    }

    private fun renderAccent(node: MathNode.Accent): String {
        val mark = LatexSymbols.ACCENT_MAP[node.command] ?: return node.toLatex()
        val base = render(node.base)
        return if (base.codePointCount(0, base.length) == 1) base + mark else node.toLatex()
    }

    private fun renderFont(node: MathNode.FontWrapper): String {
        val content = render(node.content)
        val map =
            when (node.command) {
                "mathbb" -> LatexSymbols.MATHBB_MAP
                "mathcal", "mathscr" -> LatexSymbols.MATHCAL_MAP
                "mathfrak" -> LatexSymbols.MATHFRAK_MAP
                else -> return content
            }
        return content.map { map[it] ?: it.toString() }.joinToString("")
    }

    private fun renderDelimited(node: MathNode.Delimited): String =
        delimiterToUnicode(node.left) + render(node.content) + delimiterToUnicode(node.right)

    private fun delimiterToUnicode(delimiter: String): String =
        when {
            delimiter == "." -> ""
            delimiter.startsWith("\\") -> {
                val name = delimiter.removePrefix("\\")
                LatexSymbols.DELIMITER_MAP[name] ?: LatexSymbols.COMMAND_MAP[name] ?: delimiter
            }
            else -> delimiter
        }

    private fun renderBigOperator(node: MathNode.BigOperator): String {
        val symbol = LatexSymbols.COMMAND_MAP[node.command] ?: "\\${node.command}"
        val lower = node.lower?.let { render(it).trim() }?.takeIf { it.isNotEmpty() }
        val upper = node.upper?.let { render(it).trim() }?.takeIf { it.isNotEmpty() }
        return when {
            lower != null && upper != null -> "$symbol[$lower..$upper]"
            lower != null -> "$symbol[$lower]"
            upper != null -> "$symbol[..$upper]"
            else -> symbol
        }
    }

    private fun renderEnvironment(node: MathNode.Environment): String {
        val grid = node.rows.map { row -> row.map { render(it).trim() } }.filter { it.isNotEmpty() }
        if (grid.isEmpty()) return ""
        val columnCount = grid.maxOf { it.size }
        val widths = IntArray(columnCount)
        for (row in grid) {
            for (col in row.indices) widths[col] = maxOf(widths[col], row[col].length)
        }
        val bracketed = node.name in LatexSymbols.BRACKETED_ENVIRONMENTS
        return grid.joinToString("\n") { row ->
            val cells = row.indices.joinToString("  ") { col -> row[col].padEnd(widths[col]) }
            if (bracketed) "[ ${cells.trimEnd()} ]" else cells.trimEnd()
        }
    }

    /** [s]의 모든 글자가 [map]에 있으면 치환 결과를, 한 글자라도 없으면 null을 반환한다. */
    private fun mapAllOrNull(
        s: String,
        map: Map<Char, Char>,
    ): String? {
        if (s.isEmpty()) return null
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(map[c] ?: return null)
        return sb.toString()
    }
}
