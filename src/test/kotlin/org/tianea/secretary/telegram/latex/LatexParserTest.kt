package org.tianea.secretary.telegram.latex

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LatexParserTest {
    private fun parse(input: String): List<MathNode> =
        LatexParser(LatexLexer.tokenize(input)).parse()

    private fun single(input: String): MathNode {
        val nodes = parse(input)
        assertEquals(1, nodes.size, "expected single node, got $nodes")
        return nodes[0]
    }

    @Test
    fun nestedGroupsProduceNestedGroupNodes() {
        val outer = assertIs<MathNode.Group>(single("{{x}}"))
        assertTrue(outer.braced)
        val inner = assertIs<MathNode.Group>(outer.children.single())
        assertTrue(inner.braced)
        assertEquals("x", assertIs<MathNode.Symbol>(inner.children.single()).text)
    }

    @Test
    fun fracWithNestedBracesParsesNumeratorAndDenominator() {
        val frac = assertIs<MathNode.Fraction>(single("\\frac{\\partial L}{\\partial q}"))
        assertEquals("frac", frac.kind)
        val num = assertIs<MathNode.Group>(frac.numerator)
        assertEquals("partial", assertIs<MathNode.Command>(num.children[0]).name)
        val den = assertIs<MathNode.Group>(frac.denominator)
        assertEquals("partial", assertIs<MathNode.Command>(den.children[0]).name)
    }

    @Test
    fun singleCharSuperscriptParsesAsScript() {
        val script = assertIs<MathNode.Script>(single("x^2"))
        assertEquals("x", assertIs<MathNode.Symbol>(assertNotNull(script.base)).text)
        assertEquals("2", assertIs<MathNode.Symbol>(assertNotNull(script.sup)).text)
        assertNull(script.sub)
    }

    @Test
    fun bracedSuperscriptCanNest() {
        val script = assertIs<MathNode.Script>(single("x^{e^{x}}"))
        val supGroup = assertIs<MathNode.Group>(assertNotNull(script.sup))
        assertIs<MathNode.Script>(supGroup.children.single())
    }

    @Test
    fun subAndSuperscriptOrderIsNormalized() {
        val a = assertIs<MathNode.Script>(single("x_i^2"))
        val b = assertIs<MathNode.Script>(single("x^2_i"))
        assertEquals(a.sup?.toLatex(), b.sup?.toLatex())
        assertEquals(a.sub?.toLatex(), b.sub?.toLatex())
    }

    @Test
    fun multiCharSymbolSplitsLastCharAsScriptBase() {
        val nodes = parse("abc^2")
        assertEquals("ab", assertIs<MathNode.Symbol>(nodes[0]).text)
        val script = assertIs<MathNode.Script>(nodes[1])
        assertEquals("c", assertIs<MathNode.Symbol>(assertNotNull(script.base)).text)
    }

    @Test
    fun bigOperatorAbsorbsLimits() {
        val op = assertIs<MathNode.BigOperator>(single("\\sum_{i=1}^{n}"))
        assertEquals("sum", op.command)
        assertEquals("i=1", assertNotNull(op.lower).toLatex().trim('{', '}'))
        assertEquals("n", assertNotNull(op.upper).toLatex().trim('{', '}'))
    }

    @Test
    fun leftRightParsesAsDelimited() {
        val d = assertIs<MathNode.Delimited>(single("\\left( x \\right)"))
        assertEquals("(", d.left)
        assertEquals(")", d.right)
    }

    @Test
    fun leftDotHasNullStyleDelimiter() {
        val d = assertIs<MathNode.Delimited>(single("\\left. x \\right|"))
        assertEquals(".", d.left)
        assertEquals("|", d.right)
    }

    @Test
    fun pmatrixParsesIntoRowsAndCells() {
        val env =
            assertIs<MathNode.Environment>(
                single("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
            )
        assertEquals("pmatrix", env.name)
        assertEquals(2, env.rows.size)
        assertEquals(2, env.rows[0].size)
        assertEquals(2, env.rows[1].size)
    }

    @Test
    fun accentCommandParsesAsAccent() {
        val accent = assertIs<MathNode.Accent>(single("\\dot{q}"))
        assertEquals("dot", accent.command)
    }

    @Test
    fun unknownCommandKeepsTrailingBraceArgs() {
        val unknown = assertIs<MathNode.Unknown>(single("\\unknowncmd{x}"))
        assertEquals("\\unknowncmd", unknown.raw)
        assertEquals(1, unknown.args.size)
    }

    @Test
    fun malformedInputDoesNotThrow() {
        val inputs =
            listOf(
                "{{x}",
                "\\frac{a}",
                "\\left( x",
                "\\begin{matrix} a & b",
                "x^",
                "\\end{matrix}",
                "}{}{",
            )
        for (input in inputs) {
            parse(input)
        }
    }

    @Test
    fun toLatexRoundTripsStructurally() {
        assertEquals("\\frac{a}{b}", single("\\frac{a}{b}").toLatex())
        assertEquals("\\sqrt[3]{x}", single("\\sqrt[3]{x}").toLatex())
        assertEquals("\\dot{q}", single("\\dot{q}").toLatex())
        assertEquals("\\unknowncmd{x}", single("\\unknowncmd{x}").toLatex())
    }
}
