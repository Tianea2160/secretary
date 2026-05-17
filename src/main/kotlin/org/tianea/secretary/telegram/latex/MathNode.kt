package org.tianea.secretary.telegram.latex

/**
 * [LatexParser]가 만드는 LaTeX 수식 AST.
 *
 * **불변식: 모든 노드는 [toLatex]로 원본에 준하는 LaTeX 소스를 재구성한다.** [UnicodeMathRenderer]가
 * 유니코드로 매핑하지 못하는 노드는 [toLatex]를 호출해 원본 LaTeX를 그대로 노출한다
 * (accurate-over-pretty 폴백 정책). 라운드트립은 의미상 동등이면 충분하며 바이트 단위로
 * 일치할 필요는 없다 — 폴백은 변환 *불가능* 노드에서만 의미가 있기 때문이다.
 */
internal sealed interface MathNode {
    fun toLatex(): String

    /**
     * 노드들의 시퀀스 또는 `{...}` 그룹.
     *
     * @property braced `{...}`로 명시된 그룹이면 true — [toLatex]가 중괄호를 재생성한다.
     *   인자(`\frac`의 분자 등)나 최상위 시퀀스 컨테이너는 false.
     */
    data class Group(
        val children: List<MathNode>,
        val braced: Boolean,
    ) : MathNode {
        override fun toLatex(): String {
            val inner = children.joinToString("") { it.toLatex() }
            return if (braced) "{$inner}" else inner
        }
    }

    /** 평문 문자 런(한글·숫자·기호 포함). */
    data class Symbol(
        val text: String,
    ) : MathNode {
        override fun toLatex(): String = text
    }

    /** 인자 없는 `\command`. [raw]는 렉서가 보존한 원본(`\alpha`, `\,`). */
    data class Command(
        val name: String,
        val raw: String,
    ) : MathNode {
        override fun toLatex(): String = raw
    }

    /** `\frac` / `\dfrac` / `\tfrac`. */
    data class Fraction(
        val numerator: MathNode,
        val denominator: MathNode,
        val kind: String,
    ) : MathNode {
        override fun toLatex(): String = "\\$kind{${numerator.toLatex()}}{${denominator.toLatex()}}"
    }

    /** `\sqrt{...}` 또는 `\sqrt[index]{...}`. */
    data class Sqrt(
        val radicand: MathNode,
        val index: MathNode?,
    ) : MathNode {
        override fun toLatex(): String {
            val idx = index?.let { "[${it.toLatex()}]" } ?: ""
            return "\\sqrt$idx{${radicand.toLatex()}}"
        }
    }

    /**
     * 위/아래 첨자. [base]는 leading script(`^2` 단독) 대비 nullable.
     * `x^2_i`와 `x_i^2`는 같은 노드로 모인다.
     */
    data class Script(
        val base: MathNode?,
        val sup: MathNode?,
        val sub: MathNode?,
    ) : MathNode {
        override fun toLatex(): String {
            val b = base?.toLatex() ?: ""
            val s = sub?.let { "_{${it.toLatex()}}" } ?: ""
            val p = sup?.let { "^{${it.toLatex()}}" } ?: ""
            return "$b$s$p"
        }
    }

    /** `\dot` `\hat` `\vec` `\bar` `\tilde` `\ddot` 등 액센트 명령. */
    data class Accent(
        val command: String,
        val base: MathNode,
    ) : MathNode {
        override fun toLatex(): String = "\\$command{${base.toLatex()}}"
    }

    /** `\text` `\mathrm` `\mathbf` `\mathbb` `\mathcal` `\mathfrak` 등 폰트/텍스트 래퍼. */
    data class FontWrapper(
        val command: String,
        val content: MathNode,
    ) : MathNode {
        override fun toLatex(): String = "\\$command{${content.toLatex()}}"
    }

    /**
     * `\left( ... \right)` 구분자 쌍.
     *
     * @property left `\left` 뒤 구분자 소스(`(`, `\langle`, `.`). `.`은 null 구분자.
     * @property right `\right` 뒤 구분자 소스.
     */
    data class Delimited(
        val left: String,
        val right: String,
        val content: MathNode,
    ) : MathNode {
        override fun toLatex(): String = "\\left$left${content.toLatex()}\\right$right"
    }

    /**
     * `\begin{name} ... \end{name}` 환경. [rows]의 각 원소는 한 행, 각 행의 원소는 한 셀.
     */
    data class Environment(
        val name: String,
        val rows: List<List<MathNode>>,
    ) : MathNode {
        override fun toLatex(): String {
            val body = rows.joinToString(" \\\\ ") { row -> row.joinToString(" & ") { it.toLatex() } }
            return "\\begin{$name}$body\\end{$name}"
        }
    }

    /** `\sum` `\int` `\prod` 등 큰 연산자. [lower]/[upper]는 `_`/`^`로 흡수한 상하한. */
    data class BigOperator(
        val command: String,
        val lower: MathNode?,
        val upper: MathNode?,
    ) : MathNode {
        override fun toLatex(): String {
            val l = lower?.let { "_{${it.toLatex()}}" } ?: ""
            val u = upper?.let { "^{${it.toLatex()}}" } ?: ""
            return "\\$command$l$u"
        }
    }

    /** 미인식 명령 + 뒤따른 중괄호 인자들. 원본 LaTeX를 그대로 보존한다. */
    data class Unknown(
        val raw: String,
        val args: List<MathNode>,
    ) : MathNode {
        override fun toLatex(): String = raw + args.joinToString("") { "{${it.toLatex()}}" }
    }
}
