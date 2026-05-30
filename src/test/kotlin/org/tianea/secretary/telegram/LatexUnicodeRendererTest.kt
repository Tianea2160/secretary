package org.tianea.secretary.telegram

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LatexUnicodeRendererTest {
    private val renderer = LatexUnicodeRenderer()

    @Test
    fun noDollarSignPassesThrough() {
        val input = "그냥 일반 텍스트, 변환 없음."
        assertEquals(input, renderer.render(input))
    }

    @Test
    fun inlineGreekLettersAreConverted() {
        val out = renderer.render($$"""공식: $\alpha + \beta = \gamma$ 끝.""")
        assertTrue(out.contains("α + β = γ"), "actual=$out")
        assertFalse(out.contains("\\alpha"), "원본 명령이 남으면 안 됨, actual=$out")
        assertFalse(out.contains("$"), "$ 구분자는 제거되어야 함, actual=$out")
    }

    @Test
    fun blockFormulaMatchesBeforeInline() {
        val out = renderer.render($$"""$$\sum_{i=1}^{n} i$$ 합""")
        assertTrue(out.contains("Σ"), "actual=$out")
        assertFalse(out.contains("$"), "actual=$out")
    }

    @Test
    fun singleCharSuperscriptIsConverted() {
        val out = renderer.render($$"""$x^2 + y^3$""")
        assertTrue(out.contains("x²"), "actual=$out")
        assertTrue(out.contains("y³"), "actual=$out")
    }

    @Test
    fun bracedSubscriptConvertsOnlyWhenFullyMappable() {
        val converted = renderer.render($$"""$a_{10}$""")
        assertTrue(converted.contains("a₁₀"), "숫자만 있는 첨자는 변환, actual=$converted")
        val partial = renderer.render($$"""$a_{abc}$""")
        assertTrue(
            partial.contains("a_{abc}") || partial.contains("a_abc"),
            "매핑 불가능 글자가 섞이면 원본 보존, actual=$partial",
        )
    }

    @Test
    fun fractionBecomesParenthesizedSlash() {
        val out = renderer.render($$"""$\frac{a}{b}$""")
        assertTrue(out.contains("(a)/(b)"), "actual=$out")
    }

    @Test
    fun mathbbBecomesBlackboardLetter() {
        val out = renderer.render($$"""$\mathbb{R}$ 위의 함수""")
        assertTrue(out.contains("ℝ"), "actual=$out")
    }

    @Test
    fun textWrapperExposesContentOnly() {
        val out = renderer.render($$"""$\text{if } x > 0$""")
        assertTrue(out.contains("if"), "actual=$out")
        assertFalse(out.contains("\\text"), "actual=$out")
    }

    @Test
    fun arrowCommandsBecomeUnicodeArrows() {
        val out = renderer.render($$"""$x \to \infty$""")
        assertTrue(out.contains("→"), "actual=$out")
        assertTrue(out.contains("∞"), "actual=$out")
    }

    @Test
    fun unknownLatexCommandIsKept() {
        val out = renderer.render($$"""$\unknowncmd{x}$""")
        assertTrue(out.contains("unknowncmd") || out.contains("\\unknowncmd"), "actual=$out")
    }

    @Test
    fun fibonacciRecurrenceFlattensCleanly() {
        val out = renderer.render($$"""피보나치: $F(n) = F(n-1) + F(n-2)$""")
        assertTrue(out.contains("F(n) = F(n-1) + F(n-2)"), "actual=$out")
        assertFalse(out.contains("$"), "actual=$out")
    }

    @Test
    fun dollarDelimitersAreAlwaysRemovedOnSuccess() {
        for (input in listOf($$"""$\alpha$""", $$"""$$\beta$$""", $$"""앞 $x^2$ 뒤""")) {
            assertFalse(renderer.render(input).contains("$"), "actual input=$input")
        }
    }

    @Test
    fun eulerLagrangeScreenshotFlattensEndToEnd() {
        val out =
            renderer.render(
                $$"""오일러-라그랑주: $\frac{\partial L}{\partial q} - \frac{d}{dt}\frac{\partial L}{\partial \dot{q}} = 0$"""
            )
        assertFalse(out.contains("\\frac"), "actual=$out")
        assertFalse(out.contains("$"), "actual=$out")
        assertTrue(out.contains("∂"), "actual=$out")
        assertTrue(out.contains("q̇"), "actual=$out")
    }

    @Test
    fun navierStokesScreenshotFlattensEndToEnd() {
        val out =
            renderer.render(
                $$"""$\rho\left(\frac{\partial \mathbf{v}}{\partial t} + \mathbf{v}\cdot\nabla\mathbf{v}\right) """ +
                    $$"""= -\nabla p + \mu\nabla^2\mathbf{v} + \mathbf{f}$"""
            )
        assertFalse(out.contains("\\mathbf"), "actual=$out")
        assertFalse(out.contains("\\left"), "actual=$out")
        assertFalse(out.contains("\\frac"), "actual=$out")
        assertFalse(out.contains("$"), "actual=$out")
        for (symbol in listOf("ρ", "∂", "∇", "μ", "²")) {
            assertTrue(out.contains(symbol), "missing $symbol in actual=$out")
        }
    }

    @Test
    fun renderIsIdempotentForRepresentativeInputs() {
        val inputs =
            listOf(
                "그냥 텍스트",
                $$"""$x^2 + 1$""",
                $$"""$\frac{a}{b}$""",
                $$"""앞 $\alpha$ 뒤 $\beta$""",
                $$"""$\unknowncmd{x}$""",
            )
        for (input in inputs) {
            val once = renderer.render(input)
            assertEquals(once, renderer.render(once), "not idempotent for input=$input")
        }
    }

    @Test
    fun pathologicalInputNeverThrows() {
        val inputs =
            listOf(
                "$$",
                $$"""$\$""",
                $$"""$\frac$""",
                $$"""${{{{$""",
                $$"""$}{}{$""",
                $$"""$\begin{matrix} a$""",
                $$"""$\left( x$""",
                $$"""$^_^$""",
            )
        for (input in inputs) {
            renderer.render(input)
        }
    }
}
