package org.tianea.secretary.telegram

import org.springframework.stereotype.Component
import org.tianea.secretary.telegram.latex.LatexLexer
import org.tianea.secretary.telegram.latex.LatexParser
import org.tianea.secretary.telegram.latex.UnicodeMathRenderer

/**
 * `$...$` / `$$...$$` 안의 LaTeX 수식을 유니코드 평문으로 평탄화한다.
 *
 * Telegram은 LaTeX 렌더링이 없어 [TelegramMarkdownRenderer]만 거치면 수식이 raw `$F(n)=...$`로 노출된다. 따라서 마크다운 직렬화
 * 전에 수식 구간을 토크나이저 → 재귀 하강 파서 → 유니코드 렌더러 파이프라인(`org.tianea.secretary.telegram.latex` 패키지)으로 평탄화한다.
 *
 * 변환 불가능한 토큰은 원본 LaTeX를 그대로 노출한다(accurate-over-pretty). 파서가 어떤 입력에서 예외를 던지더라도 해당 `$...$` 구간은 원본 그대로
 * 통과시켜 메시지 전송 자체는 절대 깨지지 않는다.
 *
 * 알려진 한계: `$`가 가격 표기로 쓰인 문장(`$100과 $200`)은 잘못 매칭될 수 있다. 다만 그 구간이 유효 LaTeX가 아니면 폴백으로 원본이 그대로 복원된다.
 */
@Component
class LatexUnicodeRenderer : TelegramRenderer {
    /** `$`가 없거나 blank면 파이프라인을 건너뛴다 — 일반 텍스트 메시지의 비용을 0으로. */
    override fun render(message: String): String {
        if (message.isBlank() || '$' !in message) return message
        val afterBlock = BLOCK_PATTERN.replace(message) { m -> flatten(m.groupValues[1], m.value) }
        return INLINE_PATTERN.replace(afterBlock) { m -> flatten(m.groupValues[1], m.value) }
    }

    /** LaTeX 수식 본문 [latex]를 유니코드로 평탄화한다. 파이프라인이 예외를 던지면 구분자까지 포함한 [original]을 그대로 반환한다. */
    private fun flatten(latex: String, original: String): String =
        runCatching { UnicodeMathRenderer.render(LatexParser(LatexLexer.tokenize(latex)).parse()) }
            .getOrDefault(original)

    private companion object {
        val BLOCK_PATTERN = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
        val INLINE_PATTERN = Regex("""\$([^$\n]+?)\$""")
    }
}
