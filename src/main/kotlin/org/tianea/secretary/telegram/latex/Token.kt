package org.tianea.secretary.telegram.latex

/**
 * [LatexLexer]가 생성하는 어휘 토큰.
 *
 * 모든 토큰은 원본 소스 substring [raw]를 보유한다 — 토큰 [raw]를 순서대로 이어붙이면 입력 문자열이 그대로 복원된다(round-trip). 이 불변식이
 * [MathNode]의 `toLatex()` 폴백을 떠받친다.
 */
internal sealed interface Token {
    val raw: String

    /** `\name` 형태의 명령. 단일 비문자 명령(`\,` `\{` `\ `)은 [name]이 그 한 글자다. */
    data class Command(val name: String, override val raw: String) : Token

    /** `{` */
    data object LBrace : Token {
        override val raw: String = "{"
    }

    /** `}` */
    data object RBrace : Token {
        override val raw: String = "}"
    }

    /** `^` */
    data object Caret : Token {
        override val raw: String = "^"
    }

    /** `_` */
    data object Underscore : Token {
        override val raw: String = "_"
    }

    /** `&` — 환경의 열 구분자. */
    data object Ampersand : Token {
        override val raw: String = "&"
    }

    /** `\\` — 환경의 행 구분자. 명령(`\` + 글자)과 구분된다. */
    data object RowBreak : Token {
        override val raw: String = "\\\\"
    }

    /** 평문 문자 한 개(공백·한글·숫자·기호 포함). */
    data class Char(val value: kotlin.Char) : Token {
        override val raw: String = value.toString()
    }
}
