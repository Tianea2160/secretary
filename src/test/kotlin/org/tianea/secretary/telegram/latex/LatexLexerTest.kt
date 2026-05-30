package org.tianea.secretary.telegram.latex

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LatexLexerTest {
    @Test
    fun letterCommandBecomesSingleCommandToken() {
        val tokens = LatexLexer.tokenize("\\alpha")
        assertEquals(1, tokens.size)
        val cmd = assertIs<Token.Command>(tokens[0])
        assertEquals("alpha", cmd.name)
        assertEquals("\\alpha", cmd.raw)
    }

    @Test
    fun doubleBackslashIsSingleRowBreakToken() {
        val tokens = LatexLexer.tokenize("a\\\\b")
        assertEquals(3, tokens.size)
        assertIs<Token.RowBreak>(tokens[1])
    }

    @Test
    fun structuralCharsBecomeSeparateTokens() {
        val tokens = LatexLexer.tokenize("{}^_&")
        assertIs<Token.LBrace>(tokens[0])
        assertIs<Token.RBrace>(tokens[1])
        assertIs<Token.Caret>(tokens[2])
        assertIs<Token.Underscore>(tokens[3])
        assertIs<Token.Ampersand>(tokens[4])
    }

    @Test
    fun singleNonLetterCommandBecomesCommandToken() {
        val tokens = LatexLexer.tokenize("\\,\\{\\ ")
        val c0 = assertIs<Token.Command>(tokens[0])
        assertEquals(",", c0.name)
        val c1 = assertIs<Token.Command>(tokens[1])
        assertEquals("{", c1.name)
        val c2 = assertIs<Token.Command>(tokens[2])
        assertEquals(" ", c2.name)
    }

    @Test
    fun whitespaceAfterCommandStaysAsSeparateCharToken() {
        val tokens = LatexLexer.tokenize("\\alpha x")
        assertEquals(3, tokens.size)
        assertIs<Token.Command>(tokens[0])
        assertEquals(Token.Char(' '), tokens[1])
        assertEquals(Token.Char('x'), tokens[2])
    }

    @Test
    fun joiningTokenRawRestoresOriginalInput() {
        val inputs =
            listOf(
                "\\frac{\\partial L}{\\partial \\dot{q}} = 0",
                "\\sum_{i=1}^{n} i^2",
                "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
                "한글 텍스트 \\alpha 섞임",
            )
        for (input in inputs) {
            val restored = LatexLexer.tokenize(input).joinToString("") { it.raw }
            assertEquals(input, restored, "round-trip failed: $input")
        }
    }

    @Test
    fun hangulPassesThroughAsCharTokens() {
        val tokens = LatexLexer.tokenize("가나")
        assertTrue(tokens.all { it is Token.Char }, "actual=$tokens")
    }
}
