package org.tianea.secretary.eval

import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [EvalJudge]의 다중 디코드 median-voting을 라이브 Ollama 없이 검증한다.
 *
 * judge는 점수를 `message.content`가 아니라 [OllamaChatResponse.logprobs]의 첫 1~5 토큰에서 읽으므로, fake는 디코드마다 미리
 * 만든 토큰열을 `logprobs`로 돌려준다. [SequencedFakeOllamaClient]가 디코드 순서대로 응답을 하나씩 내보내 다중 표본 투표를 시뮬레이션한다.
 */
class EvalJudgeTest {
    @Test
    fun `all-same high samples score 1`() {
        val judge =
            judgeOver(
                scoreToken("5"),
                scoreToken("5"),
                scoreToken("5"),
                scoreToken("5"),
                scoreToken("5"),
            )
        val verdict = judge.judge("q", "ref", "ans")
        assertEquals(1.0, verdict.score, EPSILON)
        assertEquals(listOf(5, 5, 5, 5, 5), verdict.samples)
        assertEquals(0.0, verdict.sampleStdDev, EPSILON)
        assertFalse(verdict.degenerate)
    }

    @Test
    fun `all-same low samples score 0`() {
        val judge =
            judgeOver(
                scoreToken("1"),
                scoreToken("1"),
                scoreToken("1"),
                scoreToken("1"),
                scoreToken("1"),
            )
        val verdict = judge.judge("q", "ref", "ans")
        assertEquals(0.0, verdict.score, EPSILON)
        assertEquals(listOf(1, 1, 1, 1, 1), verdict.samples)
        assertEquals(0.0, verdict.sampleStdDev, EPSILON)
        assertFalse(verdict.degenerate)
    }

    @Test
    fun `mixed samples are decided by median`() {
        val judge =
            judgeOver(
                scoreToken("5"),
                scoreToken("5"),
                scoreToken("3"),
                scoreToken("1"),
                scoreToken("1"),
            )
        val verdict = judge.judge("q", "ref", "ans")
        assertEquals(0.5, verdict.score, EPSILON)
        assertEquals(listOf(5, 5, 3, 1, 1), verdict.samples)
        assertTrue(verdict.sampleStdDev > 0.0, "mixed samples must have non-zero stddev")
        assertEquals(expectedPopulationStdDev(listOf(5, 5, 3, 1, 1)), verdict.sampleStdDev, EPSILON)
        assertFalse(verdict.degenerate)
    }

    @Test
    fun `decode with no score token defaults to 1 and flags degenerate without crashing`() {
        val judge =
            judgeOver(
                scoreToken("5"),
                scoreToken("5"),
                scoreToken("5"),
                scoreToken("5"),
                noScoreToken("Score:"),
            )
        val verdict = judge.judge("q", "ref", "ans")
        assertEquals(listOf(5, 5, 5, 5, 1), verdict.samples)
        assertTrue(verdict.degenerate, "a decode with no 1-5 token must flag degenerate")
        assertEquals(1.0, verdict.score, EPSILON)
    }

    @Test
    fun `all decodes with no score token default to 1 without crashing`() {
        val judge =
            judgeOver(
                noScoreToken("Score:"),
                noScoreToken("I think"),
                noScoreToken("?"),
                noScoreToken("n/a"),
                noScoreToken("..."),
            )
        val verdict = judge.judge("q", "ref", "ans")
        assertEquals(listOf(1, 1, 1, 1, 1), verdict.samples)
        assertEquals(0.0, verdict.score, EPSILON)
        assertTrue(verdict.degenerate, "all-degenerate run must flag degenerate")
    }

    @Test
    fun `score token with surrounding whitespace still parses`() {
        val judge =
            judgeOver(
                scoreToken(" 4 "),
                scoreToken(" 4 "),
                scoreToken(" 4 "),
                scoreToken(" 4 "),
                scoreToken(" 4 "),
            )
        val verdict = judge.judge("q", "ref", "ans")
        assertEquals(listOf(4, 4, 4, 4, 4), verdict.samples)
        assertEquals(0.75, verdict.score, EPSILON)
        assertFalse(verdict.degenerate)
    }

    private fun judgeOver(vararg responses: OllamaChatResponse): EvalJudge =
        EvalJudge(SequencedFakeOllamaClient(responses.toList()), model = "fake-judge")

    /** 첫 토큰이 점수인 단일 디코드 응답. */
    private fun scoreToken(token: String): OllamaChatResponse =
        OllamaChatResponse(
            message = OllamaMessage(role = "assistant", content = token),
            logprobs = listOf(OllamaTokenLogprobs(token = token, logprob = 0.0)),
        )

    /** 1~5 토큰이 전혀 없는 디코드 응답(파싱 실패 → 점수 1, degenerate). */
    private fun noScoreToken(content: String): OllamaChatResponse =
        OllamaChatResponse(
            message = OllamaMessage(role = "assistant", content = content),
            logprobs = listOf(OllamaTokenLogprobs(token = content, logprob = 0.0)),
        )

    private fun expectedPopulationStdDev(samples: List<Int>): Double {
        val mean = samples.average()
        return sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}

/**
 * 디코드 순서대로 미리 만든 [OllamaChatResponse]를 하나씩 돌려주는 [OllamaChatClient] fake.
 *
 * [EvalJudge.judge]는 케이스당 [chat]을 SAMPLE_COUNT번 호출하므로, 같은 수만큼의 응답을 순서대로 넣어 표본 투표를 시뮬레이션한다.
 */
private class SequencedFakeOllamaClient(private val responses: List<OllamaChatResponse>) :
    OllamaChatClient {
    private var index = 0

    override fun chat(body: OllamaChatRequest): OllamaChatResponse = responses[index++]
}
