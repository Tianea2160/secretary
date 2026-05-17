package org.tianea.secretary.telegram.latex

/**
 * [Token] 리스트를 [MathNode] AST로 변환하는 커서 기반 재귀 하강 파서.
 *
 * 중첩 중괄호는 호출 스택의 깊이로 자연히 처리된다([parseBracedGroup] ↔ [parseSequence] 재귀).
 * 비정상 입력(미닫힌 `{`, `\right`/`\end` 누락)에도 예외를 던지지 않고 best-effort로 파싱한다 —
 * 진입점 [LatexUnicodeRenderer]가 한 번 더 `runCatching`으로 감싸지만, 파서 자체도 관대해야 한다.
 */
internal class LatexParser(
    private val tokens: List<Token>,
) {
    private var pos = 0

    fun parse(): List<MathNode> = parseSequence(Stop.TOP)

    /** [parseSequence]가 어디서 멈출지 결정하는 컨텍스트. */
    private enum class Stop {
        /** 최상위 — EOF에서만 멈춤. */
        TOP,

        /** `{...}` 그룹 — `}`에서 멈추고 소비. */
        GROUP,

        /** 환경 셀 — `&` `\\` `\end` `}`에서 멈추되 소비하지 않음(호출자가 처리). */
        CELL,

        /** `\left..\right` 내부 — `\right`에서 멈추되 소비하지 않음. */
        DELIM,
    }

    private fun parseSequence(stop: Stop): List<MathNode> {
        val nodes = ArrayList<MathNode>()
        while (pos < tokens.size) {
            val t = tokens[pos]
            if (isTerminator(t, stop)) {
                if (stop == Stop.GROUP && t is Token.RBrace) pos++
                break
            }
            if (t is Token.Caret || t is Token.Underscore) {
                nodes.add(parseScript(detachBase(nodes)))
                continue
            }
            val before = pos
            nodes.add(parseAtom())
            if (pos == before) pos++
        }
        return nodes
    }

    private fun isTerminator(
        t: Token,
        stop: Stop,
    ): Boolean =
        when (stop) {
            Stop.TOP -> false
            Stop.GROUP -> t is Token.RBrace
            Stop.CELL -> t is Token.Ampersand || t is Token.RowBreak || t is Token.RBrace || isCommand(t, "end")
            Stop.DELIM -> isCommand(t, "right")
        }

    /**
     * `^`/`_`의 base가 될 직전 노드를 [nodes]에서 떼어낸다.
     *
     * 직전 노드가 다중 문자 [MathNode.Symbol]이면 마지막 한 글자만 base로 분리한다 — `abc^2`에서
     * `^`는 `c`에만 결합하기 때문.
     */
    private fun detachBase(nodes: MutableList<MathNode>): MathNode? {
        if (nodes.isEmpty()) return null
        val last = nodes.removeAt(nodes.size - 1)
        if (last is MathNode.Symbol && last.text.length > 1) {
            nodes.add(MathNode.Symbol(last.text.dropLast(1)))
            return MathNode.Symbol(last.text.takeLast(1))
        }
        return last
    }

    private fun parseScript(base: MathNode?): MathNode {
        var sup: MathNode? = null
        var sub: MathNode? = null
        while (pos < tokens.size && (tokens[pos] is Token.Caret || tokens[pos] is Token.Underscore)) {
            val isSup = tokens[pos] is Token.Caret
            pos++
            val arg = parseArgument()
            if (isSup) sup = arg else sub = arg
        }
        return MathNode.Script(base, sup, sub)
    }

    private fun parseAtom(): MathNode =
        when (val t = tokens[pos]) {
            is Token.Char -> {
                parseSymbolRun()
            }

            is Token.LBrace -> {
                parseBracedGroup(braced = true)
            }

            is Token.RBrace -> {
                pos++
                MathNode.Symbol("}")
            }

            is Token.Ampersand -> {
                pos++
                MathNode.Symbol("&")
            }

            is Token.RowBreak -> {
                pos++
                MathNode.Symbol("\n")
            }

            is Token.Caret, is Token.Underscore -> {
                pos++
                MathNode.Script(null, null, null)
            }

            is Token.Command -> {
                parseCommand(t)
            }
        }

    private fun parseSymbolRun(): MathNode {
        val sb = StringBuilder()
        while (pos < tokens.size && tokens[pos] is Token.Char) {
            sb.append((tokens[pos] as Token.Char).value)
            pos++
        }
        return MathNode.Symbol(sb.toString())
    }

    private fun parseBracedGroup(braced: Boolean): MathNode.Group {
        pos++
        val children = parseSequence(Stop.GROUP)
        return MathNode.Group(children, braced)
    }

    /**
     * `\frac`의 분자, `^`/`_`의 인자 등 "명령 인자 하나"를 파싱한다.
     *
     * 다음 토큰이 `{`면 그룹(중괄호는 구조적으로 소비, content-only [MathNode.Group] 반환),
     * 명령이면 그 명령 원자 하나, 문자면 단 한 글자. 선행 공백은 건너뛴다.
     */
    private fun parseArgument(): MathNode {
        skipSpaces()
        if (pos >= tokens.size) return MathNode.Group(emptyList(), braced = false)
        return when (val t = tokens[pos]) {
            is Token.LBrace -> {
                parseBracedGroup(braced = false)
            }

            is Token.Command -> {
                parseCommand(t)
            }

            is Token.Char -> {
                pos++
                MathNode.Symbol(t.value.toString())
            }

            else -> {
                MathNode.Group(emptyList(), braced = false)
            }
        }
    }

    private fun parseCommand(t: Token.Command): MathNode {
        val name = t.name
        return when {
            name == "frac" || name == "dfrac" || name == "tfrac" || name == "cfrac" -> {
                pos++
                MathNode.Fraction(parseArgument(), parseArgument(), name)
            }

            name == "sqrt" -> {
                pos++
                val index = parseOptionalArg()
                MathNode.Sqrt(parseArgument(), index)
            }

            name in LatexSymbols.ACCENT_MAP -> {
                pos++
                MathNode.Accent(name, parseArgument())
            }

            name in LatexSymbols.FONT_COMMANDS -> {
                pos++
                MathNode.FontWrapper(name, parseArgument())
            }

            name == "left" -> {
                pos++
                parseDelimited()
            }

            name == "begin" -> {
                pos++
                parseEnvironment(t.raw)
            }

            name in LatexSymbols.BIG_OPERATORS -> {
                pos++
                parseBigOperator(name)
            }

            name == "right" -> {
                pos++
                MathNode.Command(name, t.raw)
            }

            name in LatexSymbols.COMMAND_MAP -> {
                pos++
                MathNode.Command(name, t.raw)
            }

            else -> {
                pos++
                parseUnknown(t.raw)
            }
        }
    }

    private fun parseBigOperator(name: String): MathNode {
        var lower: MathNode? = null
        var upper: MathNode? = null
        while (pos < tokens.size && (tokens[pos] is Token.Caret || tokens[pos] is Token.Underscore)) {
            val isUpper = tokens[pos] is Token.Caret
            pos++
            val arg = parseArgument()
            if (isUpper) upper = arg else lower = arg
        }
        return MathNode.BigOperator(name, lower, upper)
    }

    private fun parseUnknown(raw: String): MathNode {
        val args = ArrayList<MathNode>()
        while (pos < tokens.size && tokens[pos] is Token.LBrace) {
            args.add(parseBracedGroup(braced = false))
        }
        return MathNode.Unknown(raw, args)
    }

    /** `\sqrt[index]` 의 선택 인자. 다음 토큰이 `[`가 아니면 null. */
    private fun parseOptionalArg(): MathNode? {
        if (pos >= tokens.size) return null
        val t = tokens[pos]
        if (t !is Token.Char || t.value != '[') return null
        pos++
        val children = ArrayList<MathNode>()
        while (pos < tokens.size) {
            val tk = tokens[pos]
            if (tk is Token.Char && tk.value == ']') {
                pos++
                break
            }
            when (tk) {
                is Token.Char -> {
                    children.add(MathNode.Symbol(tk.value.toString()))
                    pos++
                }

                is Token.Command -> {
                    children.add(parseCommand(tk))
                }

                is Token.LBrace -> {
                    children.add(parseBracedGroup(braced = true))
                }

                else -> {
                    pos++
                }
            }
        }
        return MathNode.Group(children, braced = false)
    }

    private fun parseDelimited(): MathNode {
        val left = parseDelimiterToken()
        val content = parseSequence(Stop.DELIM)
        var right = "."
        if (pos < tokens.size && isCommand(tokens[pos], "right")) {
            pos++
            right = parseDelimiterToken()
        }
        return MathNode.Delimited(left, right, MathNode.Group(content, braced = false))
    }

    /** `\left`/`\right` 뒤의 구분자 한 개를 소스 형태로 읽는다. `.`은 null 구분자. */
    private fun parseDelimiterToken(): String {
        skipSpaces()
        if (pos >= tokens.size) return "."
        return when (val t = tokens[pos]) {
            is Token.Char -> {
                pos++
                t.value.toString()
            }

            is Token.Command -> {
                pos++
                "\\${t.name}"
            }

            else -> {
                "."
            }
        }
    }

    private fun parseEnvironment(beginRaw: String): MathNode {
        val name = readEnvName() ?: return MathNode.Unknown(beginRaw, emptyList())
        val rows = ArrayList<List<MathNode>>()
        var row = ArrayList<MathNode>()
        var cell = ArrayList<MathNode>()

        fun flushCell() {
            row.add(MathNode.Group(cell.toList(), braced = false))
            cell = ArrayList()
        }

        fun flushRow() {
            flushCell()
            rows.add(row.toList())
            row = ArrayList()
        }

        while (pos < tokens.size) {
            cell.addAll(parseSequence(Stop.CELL))
            if (pos >= tokens.size) break
            when (val t = tokens[pos]) {
                is Token.Ampersand -> {
                    pos++
                    flushCell()
                }

                is Token.RowBreak -> {
                    pos++
                    flushRow()
                }

                is Token.RBrace -> {
                    pos++
                }

                else -> {
                    if (isCommand(t, "end")) {
                        pos++
                        readEnvName()
                    }
                    break
                }
            }
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) flushRow()
        return MathNode.Environment(name, rows)
    }

    /** `{name}` 형태의 환경 이름을 읽는다. 다음 토큰이 `{`가 아니면 null. */
    private fun readEnvName(): String? {
        skipSpaces()
        if (pos >= tokens.size || tokens[pos] !is Token.LBrace) return null
        pos++
        val sb = StringBuilder()
        while (pos < tokens.size && tokens[pos] !is Token.RBrace) {
            when (val t = tokens[pos]) {
                is Token.Char -> {
                    sb.append(t.value)
                }

                is Token.Command -> {
                    sb.append(t.name)
                }

                else -> {}
            }
            pos++
        }
        if (pos < tokens.size && tokens[pos] is Token.RBrace) pos++
        return sb.toString()
    }

    private fun skipSpaces() {
        while (pos < tokens.size) {
            val t = tokens[pos]
            if (t is Token.Char && (t.value == ' ' || t.value == '\t')) pos++ else break
        }
    }

    private fun isCommand(
        t: Token,
        name: String,
    ): Boolean = t is Token.Command && t.name == name
}
