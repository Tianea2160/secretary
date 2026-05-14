package org.tianea.secretary.telegram

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatexUnicodeRendererTest {
    private val renderer = LatexUnicodeRenderer()

    @Test
    fun `달러 기호가 없으면 그대로 통과한다`() {
        val input = "그냥 일반 텍스트, 변환 없음."
        assertEquals(input, renderer.render(input))
    }

    @Test
    fun `inline 수식의 그리스 문자는 유니코드로 치환된다`() {
        val out = renderer.render($$"""공식: $\alpha + \beta = \gamma$ 끝.""")
        assertTrue(out.contains("α + β = γ"), "actual=$out")
        assertFalse(out.contains("\\alpha"), "원본 명령이 남으면 안 됨, actual=$out")
        assertFalse(out.contains("$"), "$ 구분자는 제거되어야 함, actual=$out")
    }

    @Test
    fun `block 수식은 inline보다 먼저 매칭된다`() {
        val out = renderer.render($$"""$$\sum_{i=1}^{n} i$$ 합""")
        assertTrue(out.contains("Σ"), "actual=$out")
        assertFalse(out.contains("$"), "actual=$out")
    }

    @Test
    fun `한 글자 위첨자는 유니코드 superscript로 변환된다`() {
        val out = renderer.render($$"""$x^2 + y^3$""")
        assertTrue(out.contains("x²"), "actual=$out")
        assertTrue(out.contains("y³"), "actual=$out")
    }

    @Test
    fun `중괄호 아래첨자는 모든 글자가 매핑될 때만 변환된다`() {
        val converted = renderer.render($$"""$a_{10}$""")
        assertTrue(converted.contains("a₁₀"), "숫자만 있는 첨자는 변환, actual=$converted")
        val partial = renderer.render($$"""$a_{abc}$""")
        assertTrue(
            partial.contains("a_{abc}") || partial.contains("a_abc"),
            "매핑 불가능 글자(b, c)가 섞이면 원본 보존되어야 함, actual=$partial",
        )
    }

    @Test
    fun `분수는 괄호 둘러싼 슬래시로 변환된다`() {
        val out = renderer.render($$"""$\frac{a}{b}$""")
        assertTrue(out.contains("(a)/(b)"), "actual=$out")
    }

    @Test
    fun `mathbb는 블랙보드 문자로 변환된다`() {
        val out = renderer.render($$"""$\mathbb{R}$ 위의 함수""")
        assertTrue(out.contains("ℝ"), "actual=$out")
    }

    @Test
    fun `text 래퍼는 내용만 노출된다`() {
        val out = renderer.render($$"""$\text{if } x > 0$""")
        assertTrue(out.contains("if"), "actual=$out")
        assertFalse(out.contains("\\text"), "actual=$out")
    }

    @Test
    fun `화살표 명령은 유니코드 화살표로 변환된다`() {
        val out = renderer.render($$"""$x \to \infty$""")
        assertTrue(out.contains("→"), "actual=$out")
        assertTrue(out.contains("∞"), "actual=$out")
    }

    @Test
    fun `알 수 없는 LaTeX 명령은 원문 유지된다`() {
        val out = renderer.render($$"""$\unknowncmd{x}$""")
        assertTrue(out.contains("unknowncmd") || out.contains("\\unknowncmd"), "actual=$out")
    }

    @Test
    fun `피보나치 점화식 케이스는 깨끗하게 평탄화된다`() {
        val out = renderer.render($$"""피보나치: $F(n) = F(n-1) + F(n-2)$""")
        assertTrue(out.contains("F(n) = F(n-1) + F(n-2)"), "actual=$out")
        assertFalse(out.contains("$"), "actual=$out")
    }
}
