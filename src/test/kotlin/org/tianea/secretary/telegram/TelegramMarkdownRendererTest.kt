package org.tianea.secretary.telegram

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramMarkdownRendererTest {
    private val renderer = TelegramMarkdownRenderer()

    @Test
    fun `헤더는 굵게로 변환된다`() {
        val out = renderer.render("# Title\n\nbody")
        assertTrue(out.startsWith("*Title*"), "헤더가 굵게로 변환되어야 함, actual=$out")
    }

    @Test
    fun `굵게는 별표 한 개로 직렬화된다`() {
        val out = renderer.render("**bold** text")
        assertTrue(out.contains("*bold*"), "actual=$out")
    }

    @Test
    fun `기울임은 언더스코어로 직렬화된다`() {
        val out = renderer.render("*italic* word")
        assertTrue(out.contains("_italic_"), "actual=$out")
    }

    @Test
    fun `예약 문자는 백슬래시로 이스케이프된다`() {
        val out = renderer.render("price is 1.0 (USD)!")
        assertTrue(out.contains("1\\.0"), "마침표 이스케이프, actual=$out")
        assertTrue(out.contains("\\(USD\\)"), "괄호 이스케이프, actual=$out")
        assertTrue(out.contains("\\!"), "느낌표 이스케이프, actual=$out")
    }

    @Test
    fun `GFM 표는 fenced code block 안에 정렬된다`() {
        val md =
            """
            | N | F(n) |
            |---|------|
            | 0 | 0 |
            | 1 | 1 |
            """.trimIndent()
        val out = renderer.render(md)
        assertTrue(out.contains("```"), "코드블록 fence가 있어야 함, actual=$out")
        assertTrue(out.contains("N"), "헤더 셀 유지, actual=$out")
        assertTrue(out.contains("F(n)"), "헤더 셀 유지(괄호 escape는 코드블록 내라 불필요), actual=$out")
    }

    @Test
    fun `LaTeX는 일반 텍스트로 escape 된다`() {
        val out = renderer.render($$"formula: $F(n) = F(n-1) + F(n-2)$")
        assertTrue(out.contains("\\("), "괄호 escape, actual=$out")
        assertTrue(out.contains("\\-"), "하이픈 escape, actual=$out")
        assertTrue(out.contains("\\+"), "플러스 escape, actual=$out")
    }

    @Test
    fun `unordered list는 글머리표로 변환된다`() {
        val out = renderer.render("- one\n- two")
        assertTrue(out.contains("• one"), "actual=$out")
        assertTrue(out.contains("• two"), "actual=$out")
    }

    @Test
    fun `ordered list는 escaped dot으로 변환된다`() {
        val out = renderer.render("1. one\n2. two")
        assertTrue(out.contains("1\\. one"), "actual=$out")
        assertTrue(out.contains("2\\. two"), "actual=$out")
    }

    @Test
    fun `fenced code block은 보존되고 내용은 안전하게 escape 된다`() {
        val md =
            """
            ```kotlin
            val x = 1
            ```
            """.trimIndent()
        val out = renderer.render(md)
        assertTrue(out.contains("```kotlin"), "info string 유지, actual=$out")
        assertTrue(out.contains("val x = 1"), "내용 보존, actual=$out")
    }

    @Test
    fun `링크는 텍스트 escape와 url escape를 분리한다`() {
        val out = renderer.render("see [docs](https://example.com/a.b)")
        assertTrue(out.contains("[docs]"), "텍스트 보존, actual=$out")
        assertTrue(out.contains("(https://example.com/a.b)"), "URL은 점 escape 안 함, actual=$out")
    }

    @Test
    fun `blank input은 그대로 반환된다`() {
        assertEquals("", renderer.render(""))
        assertEquals("   ", renderer.render("   "))
    }
}
