package org.tianea.secretary.telegram.latex

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UnicodeMathRendererTest {
    private fun render(input: String): String =
        UnicodeMathRenderer.render(LatexParser(LatexLexer.tokenize(input)).parse())

    @Test
    fun greekLettersAreConverted() {
        assertEquals("α + β = γ", render("\\alpha + \\beta = \\gamma"))
        assertEquals("Δ Ω", render("\\Delta \\Omega"))
    }

    @Test
    fun operatorsAndRelationsAreConverted() {
        assertEquals("≤", render("\\leq"))
        assertEquals("×", render("\\times"))
        assertEquals("±", render("\\pm"))
        assertEquals("·", render("\\cdot"))
    }

    @Test
    fun arrowsAreConverted() {
        assertEquals("→", render("\\to"))
        assertEquals("⇒", render("\\Rightarrow"))
        assertEquals("↦", render("\\mapsto"))
    }

    @Test
    fun setAndLogicSymbolsAreConverted() {
        assertEquals("∈", render("\\in"))
        assertEquals("⊂", render("\\subset"))
        assertEquals("∪", render("\\cup"))
        assertEquals("∀", render("\\forall"))
        assertEquals("∅", render("\\emptyset"))
    }

    @Test
    fun superscriptAndSubscriptSucceedWhenFullyMappable() {
        assertEquals("x²", render("x^2"))
        assertEquals("a₁₀", render("a_{10}"))
        assertEquals("y³", render("y^3"))
    }

    @Test
    fun nestedSuperscriptConvertsInnerLevel() {
        val out = render("x^{e^{x}}")
        assertFalse(out.contains("\\"), "actual=$out")
        assertTrue(out.contains("eˣ"), "actual=$out")
    }

    @Test
    fun scriptFallsBackToLiteralWhenNotFullyMappable() {
        assertEquals("a_{abc}", render("a_{abc}"))
        assertEquals("x^{2Y}", render("x^{2Y}"))
    }

    @Test
    fun fractionsAreConverted() {
        assertEquals("(a)/(b)", render("\\frac{a}{b}"))
        assertEquals("(a)/(b)", render("\\dfrac{a}{b}"))
        assertEquals("((a)/(b))/(c)", render("\\frac{\\frac{a}{b}}{c}"))
    }

    @Test
    fun sqrtIsConverted() {
        assertEquals("√(x)", render("\\sqrt{x}"))
        assertEquals("³√(x)", render("\\sqrt[3]{x}"))
    }

    @Test
    fun bigOperatorLimitsRenderInBrackets() {
        assertEquals("Σ[i=1..n]", render("\\sum_{i=1}^{n}"))
        assertEquals("∫[0..1]", render("\\int_0^1"))
        assertEquals("∏[k]", render("\\prod_{k}"))
    }

    @Test
    fun accentsUseCombiningDiacriticalMarks() {
        assertEquals("q̇", render("\\dot{q}"))
        assertEquals("v⃗", render("\\vec{v}"))
        assertEquals("x̂", render("\\hat{x}"))
    }

    @Test
    fun accentFallsBackToLiteralForMultiCharBase() {
        assertEquals("\\vec{ab}", render("\\vec{ab}"))
    }

    @Test
    fun fontWrappersExposeContentWithoutLeakingCommand() {
        assertEquals("v", render("\\mathbf{v}"))
        assertEquals("ℝ", render("\\mathbb{R}"))
        assertEquals("d", render("\\mathrm{d}"))
        assertEquals("ℒ", render("\\mathcal{L}"))
    }

    @Test
    fun leftRightDelimitersAreConverted() {
        assertEquals("((a)/(b))", render("\\left(\\frac{a}{b}\\right)"))
        assertEquals("(a)/(b)|", render("\\left.\\frac{a}{b}\\right|"))
    }

    @Test
    fun matrixEnvironmentIsBracketedRowPerLine() {
        val out = render("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
        assertEquals("[ a  b ]\n[ c  d ]", out)
    }

    @Test
    fun casesEnvironmentSplitsRowsByNewline() {
        val out = render("\\begin{cases} x & x>0 \\\\ -x & x<0 \\end{cases}")
        assertTrue(out.contains("\n"), "actual=$out")
        assertTrue(out.contains("x>0"), "actual=$out")
        assertTrue(out.contains("-x"), "actual=$out")
    }

    @Test
    fun spacingCommandsBecomeSpaces() {
        assertEquals("a b", render("a\\,b"))
        assertEquals("  ", render("\\quad"))
    }

    @Test
    fun unknownCommandFallsBackToOriginalLatex() {
        assertEquals("\\unknowncmd{x}", render("\\unknowncmd{x}"))
        assertEquals("\\foo", render("\\foo"))
    }

    @Test
    fun eulerLagrangeScreenshotFlattensCleanly() {
        val out =
            render(
                "\\frac{\\partial L}{\\partial q} - \\frac{d}{dt}\\frac{\\partial L}{\\partial \\dot{q}} = 0"
            )
        assertFalse(out.contains("frac"), "actual=$out")
        assertTrue(out.contains("∂"), "actual=$out")
        assertTrue(out.contains("q̇"), "actual=$out")
        assertTrue(out.contains("= 0"), "actual=$out")
    }

    @Test
    fun navierStokesScreenshotFlattensCleanly() {
        val out =
            render(
                "\\rho\\left(\\frac{\\partial \\mathbf{v}}{\\partial t} + \\mathbf{v}\\cdot\\nabla\\mathbf{v}\\right)" +
                    " = -\\nabla p + \\mu\\nabla^2\\mathbf{v} + \\mathbf{f}"
            )
        assertFalse(out.contains("mathbf"), "actual=$out")
        assertFalse(out.contains("\\left"), "actual=$out")
        assertFalse(out.contains("\\right"), "actual=$out")
        assertFalse(out.contains("frac"), "actual=$out")
        for (symbol in listOf("ρ", "∂", "∇", "μ", "²")) {
            assertTrue(out.contains(symbol), "missing $symbol in actual=$out")
        }
    }
}
