package org.tianea.secretary.telegram

import org.springframework.stereotype.Component

/**
 * `$...$` / `$$...$$` 안의 LaTeX 토큰을 유니코드 문자로 평탄화한다.
 *
 * Telegram은 LaTeX 렌더링이 없어 [TelegramMarkdownRenderer]만 거치면 수식이 raw `$F(n)=...$`로
 * 노출되므로, 마크다운 파싱 전에 자주 쓰이는 그리스 문자·수학 기호·첨자를 유니코드 문자로 치환해
 * 일반 텍스트로 평탄화한다. 완전한 LaTeX 파서가 아니라 LLM이 흔히 쓰는 토큰만 다룬다.
 *
 * 지원 범위:
 * - 그리스 문자(`\alpha`–`\omega`, 대문자, `\var*` 변종)
 * - 수학 기호·관계자·화살표(`\sum`, `\int`, `\leq`, `\to`, `\infty`, ...)
 * - `\mathbb{R}` → ℝ, `\mathbb{N}` → ℕ 등 일부 블랙보드
 * - 위/아래 첨자(`^2`, `_n`, `^{10}`, `_{i+1}`) — 모든 글자가 유니코드 super/subscript에
 *   매핑될 때만 변환, 일부라도 매핑이 없으면 원본을 그대로 둔다
 * - 분수(`\frac{a}{b}` → `(a)/(b)`)
 * - 텍스트 래퍼(`\text{...}`, `\mathrm{...}`, `\operatorname{...}`)는 내용만 노출
 *
 * 알려진 한계: nested brace(`^{e^{x}}`)는 outer만 처리. `\left`·`\right`는 제거.
 * `$`가 가격 표기로 쓰인 문장(`$100과 $200`)은 잘못 매칭될 수 있다.
 */
@Component
class LatexUnicodeRenderer : TelegramRenderer {
    /** `$`가 없거나 blank면 regex 파이프라인을 건너뛴다 — 일반 텍스트 메시지의 비용을 0으로. */
    override fun render(message: String): String {
        if (message.isBlank() || '$' !in message) return message
        val afterBlock = BLOCK_PATTERN.replace(message) { m -> transform(m.groupValues[1]) }
        return INLINE_PATTERN.replace(afterBlock) { m -> transform(m.groupValues[1]) }
    }

    private fun transform(latex: String): String {
        var t = latex
        t = TEXT_WRAPPER.replace(t) { it.groupValues[2] }
        t = MATHBB_PATTERN.replace(t) { MATHBB_MAP[it.groupValues[1]] ?: it.groupValues[1] }
        t = FRAC_PATTERN.replace(t) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
        t = COMMAND_PATTERN.replace(t) { m -> COMMAND_MAP[m.groupValues[1]] ?: m.value }
        t = SUP_BRACE_PATTERN.replace(t) { m -> mapAllOrNull(m.groupValues[1], SUPERSCRIPT_MAP) ?: m.value }
        t = SUB_BRACE_PATTERN.replace(t) { m -> mapAllOrNull(m.groupValues[1], SUBSCRIPT_MAP) ?: m.value }
        t = SUP_CHAR_PATTERN.replace(t) { m -> mapAllOrNull(m.groupValues[1], SUPERSCRIPT_MAP) ?: m.value }
        t = SUB_CHAR_PATTERN.replace(t) { m -> mapAllOrNull(m.groupValues[1], SUBSCRIPT_MAP) ?: m.value }
        t = t.replace("{", "").replace("}", "")
        return t.trim()
    }

    /** 모든 글자가 [map]에 있을 때만 치환 결과를 반환, 한 글자라도 없으면 null. */
    private fun mapAllOrNull(
        s: String,
        map: Map<Char, Char>,
    ): String? {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(map[c] ?: return null)
        return sb.toString()
    }

    private companion object {
        val BLOCK_PATTERN = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
        val INLINE_PATTERN = Regex("""\$([^$\n]+?)\$""")
        val TEXT_WRAPPER = Regex("""\\(text|mathrm|operatorname)\{([^{}]*)}""")
        val MATHBB_PATTERN = Regex("""\\mathbb\{([A-Z])}""")
        val FRAC_PATTERN = Regex("""\\frac\{([^{}]+)}\{([^{}]+)}""")
        val COMMAND_PATTERN = Regex("""\\([a-zA-Z]+)""")
        val SUP_BRACE_PATTERN = Regex("""\^\{([^{}]+)}""")
        val SUB_BRACE_PATTERN = Regex("""_\{([^{}]+)}""")
        val SUP_CHAR_PATTERN = Regex("""\^([a-zA-Z0-9+\-=()])""")
        val SUB_CHAR_PATTERN = Regex("""_([a-zA-Z0-9+\-=()])""")

        val COMMAND_MAP: Map<String, String> =
            mapOf(
                "alpha" to "α",
                "beta" to "β",
                "gamma" to "γ",
                "delta" to "δ",
                "epsilon" to "ε",
                "varepsilon" to "ε",
                "zeta" to "ζ",
                "eta" to "η",
                "theta" to "θ",
                "vartheta" to "ϑ",
                "iota" to "ι",
                "kappa" to "κ",
                "lambda" to "λ",
                "mu" to "μ",
                "nu" to "ν",
                "xi" to "ξ",
                "omicron" to "ο",
                "pi" to "π",
                "varpi" to "ϖ",
                "rho" to "ρ",
                "varrho" to "ϱ",
                "sigma" to "σ",
                "varsigma" to "ς",
                "tau" to "τ",
                "upsilon" to "υ",
                "phi" to "φ",
                "varphi" to "ϕ",
                "chi" to "χ",
                "psi" to "ψ",
                "omega" to "ω",
                "Alpha" to "Α",
                "Beta" to "Β",
                "Gamma" to "Γ",
                "Delta" to "Δ",
                "Epsilon" to "Ε",
                "Zeta" to "Ζ",
                "Eta" to "Η",
                "Theta" to "Θ",
                "Iota" to "Ι",
                "Kappa" to "Κ",
                "Lambda" to "Λ",
                "Mu" to "Μ",
                "Nu" to "Ν",
                "Xi" to "Ξ",
                "Omicron" to "Ο",
                "Pi" to "Π",
                "Rho" to "Ρ",
                "Sigma" to "Σ",
                "Tau" to "Τ",
                "Upsilon" to "Υ",
                "Phi" to "Φ",
                "Chi" to "Χ",
                "Psi" to "Ψ",
                "Omega" to "Ω",
                "pm" to "±",
                "mp" to "∓",
                "times" to "×",
                "div" to "÷",
                "cdot" to "·",
                "ast" to "∗",
                "star" to "⋆",
                "leq" to "≤",
                "le" to "≤",
                "geq" to "≥",
                "ge" to "≥",
                "neq" to "≠",
                "ne" to "≠",
                "approx" to "≈",
                "equiv" to "≡",
                "sim" to "∼",
                "simeq" to "≃",
                "cong" to "≅",
                "propto" to "∝",
                "to" to "→",
                "leftarrow" to "←",
                "rightarrow" to "→",
                "leftrightarrow" to "↔",
                "Rightarrow" to "⇒",
                "Leftarrow" to "⇐",
                "Leftrightarrow" to "⇔",
                "mapsto" to "↦",
                "longrightarrow" to "⟶",
                "longleftarrow" to "⟵",
                "infty" to "∞",
                "partial" to "∂",
                "nabla" to "∇",
                "forall" to "∀",
                "exists" to "∃",
                "nexists" to "∄",
                "in" to "∈",
                "notin" to "∉",
                "ni" to "∋",
                "subset" to "⊂",
                "supset" to "⊃",
                "subseteq" to "⊆",
                "supseteq" to "⊇",
                "cup" to "∪",
                "cap" to "∩",
                "setminus" to "∖",
                "emptyset" to "∅",
                "varnothing" to "∅",
                "sum" to "Σ",
                "prod" to "∏",
                "coprod" to "∐",
                "int" to "∫",
                "iint" to "∬",
                "iiint" to "∭",
                "oint" to "∮",
                "sqrt" to "√",
                "surd" to "√",
                "land" to "∧",
                "wedge" to "∧",
                "lor" to "∨",
                "vee" to "∨",
                "lnot" to "¬",
                "neg" to "¬",
                "top" to "⊤",
                "bot" to "⊥",
                "perp" to "⊥",
                "parallel" to "∥",
                "angle" to "∠",
                "triangle" to "△",
                "square" to "□",
                "cdots" to "⋯",
                "ldots" to "…",
                "vdots" to "⋮",
                "ddots" to "⋱",
                "circ" to "∘",
                "bullet" to "•",
                "oplus" to "⊕",
                "otimes" to "⊗",
                "ominus" to "⊖",
                "oslash" to "⊘",
                "odot" to "⊙",
                "hbar" to "ℏ",
                "ell" to "ℓ",
                "Re" to "ℜ",
                "Im" to "ℑ",
                "aleph" to "ℵ",
                "beth" to "ℶ",
                "quad" to " ",
                "qquad" to "  ",
                "left" to "",
                "right" to "",
            )

        val MATHBB_MAP: Map<String, String> =
            mapOf(
                "R" to "ℝ",
                "N" to "ℕ",
                "Z" to "ℤ",
                "Q" to "ℚ",
                "C" to "ℂ",
                "P" to "ℙ",
                "H" to "ℍ",
                "F" to "𝔽",
                "K" to "𝕂",
            )

        val SUPERSCRIPT_MAP: Map<Char, Char> =
            mapOf(
                '0' to '⁰',
                '1' to '¹',
                '2' to '²',
                '3' to '³',
                '4' to '⁴',
                '5' to '⁵',
                '6' to '⁶',
                '7' to '⁷',
                '8' to '⁸',
                '9' to '⁹',
                '+' to '⁺',
                '-' to '⁻',
                '=' to '⁼',
                '(' to '⁽',
                ')' to '⁾',
                'a' to 'ᵃ',
                'b' to 'ᵇ',
                'c' to 'ᶜ',
                'd' to 'ᵈ',
                'e' to 'ᵉ',
                'f' to 'ᶠ',
                'g' to 'ᵍ',
                'h' to 'ʰ',
                'i' to 'ⁱ',
                'j' to 'ʲ',
                'k' to 'ᵏ',
                'l' to 'ˡ',
                'm' to 'ᵐ',
                'n' to 'ⁿ',
                'o' to 'ᵒ',
                'p' to 'ᵖ',
                'r' to 'ʳ',
                's' to 'ˢ',
                't' to 'ᵗ',
                'u' to 'ᵘ',
                'v' to 'ᵛ',
                'w' to 'ʷ',
                'x' to 'ˣ',
                'y' to 'ʸ',
                'z' to 'ᶻ',
            )

        val SUBSCRIPT_MAP: Map<Char, Char> =
            mapOf(
                '0' to '₀',
                '1' to '₁',
                '2' to '₂',
                '3' to '₃',
                '4' to '₄',
                '5' to '₅',
                '6' to '₆',
                '7' to '₇',
                '8' to '₈',
                '9' to '₉',
                '+' to '₊',
                '-' to '₋',
                '=' to '₌',
                '(' to '₍',
                ')' to '₎',
                'a' to 'ₐ',
                'e' to 'ₑ',
                'h' to 'ₕ',
                'i' to 'ᵢ',
                'j' to 'ⱼ',
                'k' to 'ₖ',
                'l' to 'ₗ',
                'm' to 'ₘ',
                'n' to 'ₙ',
                'o' to 'ₒ',
                'p' to 'ₚ',
                'r' to 'ᵣ',
                's' to 'ₛ',
                't' to 'ₜ',
                'u' to 'ᵤ',
                'v' to 'ᵥ',
                'x' to 'ₓ',
            )
    }
}
