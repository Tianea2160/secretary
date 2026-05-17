package org.tianea.secretary.telegram.latex

/**
 * LaTeX 수식 문자열을 [Token] 리스트로 분해하는 단일 전진 패스 렉서. 정규식을 쓰지 않는다.
 *
 * 규칙:
 * - `\\` → [Token.RowBreak] (명령보다 먼저 검사)
 * - `\` + 글자들 → [Token.Command] (`\alpha`, `\frac`)
 * - `\` + 비문자 한 글자 → [Token.Command] (`\,`, `\{`, `\ `)
 * - `{` `}` `^` `_` `&` → 각각의 구조 토큰
 * - 그 외 모든 문자 → [Token.Char] (공백·한글 포함, 그대로 보존)
 *
 * 공백은 명령 뒤에서도 소비하지 않고 [Token.Char]로 남긴다 — `\alpha + \beta`가 `α + β`로
 * 렌더되도록(기존 동작 유지).
 */
internal object LatexLexer {
    fun tokenize(input: String): List<Token> {
        val tokens = ArrayList<Token>(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' -> {
                    i = lexBackslash(input, i, tokens)
                }

                c == '{' -> {
                    tokens.add(Token.LBrace)
                    i++
                }

                c == '}' -> {
                    tokens.add(Token.RBrace)
                    i++
                }

                c == '^' -> {
                    tokens.add(Token.Caret)
                    i++
                }

                c == '_' -> {
                    tokens.add(Token.Underscore)
                    i++
                }

                c == '&' -> {
                    tokens.add(Token.Ampersand)
                    i++
                }

                else -> {
                    tokens.add(Token.Char(c))
                    i++
                }
            }
        }
        return tokens
    }

    /**
     * `input[start]`가 `\`일 때 백슬래시 토큰을 소비하고 다음 인덱스를 반환한다.
     *
     * `\` 단독으로 문자열이 끝나면 평문 `\` 한 글자로 처리한다.
     */
    private fun lexBackslash(
        input: String,
        start: Int,
        tokens: MutableList<Token>,
    ): Int {
        val next = start + 1
        if (next >= input.length) {
            tokens.add(Token.Char('\\'))
            return next
        }
        val nc = input[next]
        if (nc == '\\') {
            tokens.add(Token.RowBreak)
            return start + 2
        }
        if (nc.isLetter()) {
            var j = next
            while (j < input.length && input[j].isLetter()) j++
            val name = input.substring(next, j)
            tokens.add(Token.Command(name, input.substring(start, j)))
            return j
        }
        tokens.add(Token.Command(nc.toString(), input.substring(start, start + 2)))
        return start + 2
    }
}
