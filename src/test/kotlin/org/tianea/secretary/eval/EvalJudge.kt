package org.tianea.secretary.eval

import kotlin.math.sqrt

/**
 * 다중 디코드 median-voting LLM-as-a-judge.
 *
 * judge에게 기준답안 대비 정답성을 **1~5 정수 한 글자**로만 출력하게 하고, 같은 프롬프트를 [SAMPLE_COUNT]번 독립
 * 디코드([JUDGE_TEMPERATURE], 샘플마다 다른 seed)해 각 디코드의 첫 1~5 점수 토큰을 모은다. 최종 점수는 표본의 median을
 * `(median−1)/4`로 0~1로 정규화한 값이다.
 *
 * 단일 디코드의 흔들림을 평균이 아닌 median으로 흡수해 이상치(degenerate 디코드)에 강건하게 만든다. 점수를 끝내 못 내는 디코드는 사용자 의도("점수를 계산할
 * 수 없으면 1로 판단")에 따라 점수 1로 보고 [JudgeVerdict.degenerate]를 켠다 — 절대 예외를 던지지 않고, 모델이 표현하지 않은 확률을 지어내지
 * 않는다.
 *
 * Ollama는 logprobs를 주지만 Koog `PromptExecutor`(Spring AI 브릿지)가 이를 응답 객체·trace 양쪽에서 버리므로
 * (`KoogLogprobsProbe`로 실측), Koog가 아니라 [OllamaChatClient]로 Ollama `/api/chat`를 직접 호출한다.
 *
 * @param ollama Ollama 직접 호출 클라이언트.
 * @param model 채점에 쓸 Ollama 모델 id. 응답 모델과 분리해 고정하는 것을 권장.
 */
class EvalJudge(private val ollama: OllamaChatClient, private val model: String) {
    /**
     * 한 평가 케이스를 [SAMPLE_COUNT]번 독립 디코드해 채점한다.
     *
     * @param question 데이터셋 item의 입력.
     * @param referenceAnswer 기대 정답.
     * @param actualOutput 어시스턴트가 실제로 생성한 응답.
     * @return median 기반 0.0~1.0 정답성 점수와 표본 내역. 어떤 디코드도 1~5를 못 내면 그 디코드는 점수 1로 집계되고
     *   [JudgeVerdict.degenerate]가 true가 된다.
     */
    fun judge(question: String, referenceAnswer: String, actualOutput: String): JudgeVerdict {
        val userPrompt = buildUserPrompt(question, referenceAnswer, actualOutput)
        var degenerate = false
        val samples =
            (0 until SAMPLE_COUNT).map { i ->
                val response =
                    ollama.chat(
                        OllamaChatRequest(
                            model = model,
                            messages =
                                listOf(
                                    OllamaMessage(role = "system", content = JUDGE_SYSTEM_PROMPT),
                                    OllamaMessage(role = "user", content = userPrompt),
                                ),
                            options = OllamaOptions(temperature = JUDGE_TEMPERATURE, seed = i),
                        )
                    )
                val parsed = firstScore(response)
                if (parsed == null) {
                    degenerate = true
                    1
                } else {
                    parsed
                }
            }

        val median = median(samples)
        val stdDev = populationStdDev(samples)
        return JudgeVerdict(
            score = (median - 1.0) / 4.0,
            reasoning = buildReasoning(median, samples, stdDev, degenerate),
            samples = samples,
            sampleStdDev = stdDev,
            degenerate = degenerate,
        )
    }

    private fun buildUserPrompt(
        question: String,
        referenceAnswer: String,
        actualOutput: String,
    ): String = buildString {
        appendLine("## Question")
        appendLine(question)
        appendLine()
        appendLine("## Reference answer")
        appendLine(referenceAnswer)
        appendLine()
        appendLine("## Assistant answer (to grade)")
        appendLine(actualOutput)
        appendLine()
        append("Output ONLY a single integer from 1 to 5. No other text.")
    }

    /** 한 디코드의 **첫 1~5 점수 토큰**(선행 공백/개행 토큰은 건너뜀)을 정수로. 없으면 null. */
    private fun firstScore(response: OllamaChatResponse): Int? =
        response.logprobs.firstNotNullOfOrNull { it.token.asScore() }

    /** 표본 median. 짝수 N이면 가운데 두 값의 산술평균(결과가 정수가 아니어도 무방 — 곧바로 `(median−1)/4`로 정규화하므로 연속값이 허용된다). */
    private fun median(samples: List<Int>): Double {
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid].toDouble()
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    private fun populationStdDev(samples: List<Int>): Double {
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        return sqrt(variance)
    }

    /** reasoning 텍스트. median·표본·표준편차를 요약하고 degenerate면 그 사실을 명시한다. */
    private fun buildReasoning(
        median: Double,
        samples: List<Int>,
        stdDev: Double,
        degenerate: Boolean,
    ): String = buildString {
        append(
            "median %.1f/5 over %d samples %s (stddev %.3f)"
                .format(median, samples.size, samples, stdDev)
        )
        if (degenerate) {
            append(" [degenerate: at least one decode produced no 1-5 token, scored 1]")
        }
    }

    /** 토큰 텍스트가 1~5 점수면 그 정수를, 아니면 null. 선행/후행 공백 토큰을 허용한다. */
    private fun String.asScore(): Int? = trim().toIntOrNull()?.takeIf { it in 1..5 }

    private companion object {
        /** 케이스당 독립 디코드 수. median 안정성과 호출 비용의 절충값(경험적으로 조정 가능한 노브). */
        const val SAMPLE_COUNT = 5

        /** 샘플 다양성을 내기 위한 디코드 온도. 0 초과여야 의미 있다(경험적으로 조정 가능한 노브). */
        const val JUDGE_TEMPERATURE = 0.3

        val JUDGE_SYSTEM_PROMPT =
            """
            You are a strict evaluator grading an assistant's answer against a reference answer.

            Grade ONLY on semantic correctness and completeness relative to the reference — not on
            wording, style, or length. The assistant may phrase things differently; what matters is
            whether it conveys the same correct information the reference does.

            Scoring rubric (integer 1–5):
            - 5: fully correct and complete; matches the reference's substance.
            - 4: mostly correct; minor omission or imprecision.
            - 3: partially correct; misses or distorts a meaningful part.
            - 2: largely incorrect or off-topic, with a small grain of relevance.
            - 1: wrong, empty, or contradicts the reference.

            Output ONLY the single integer (1, 2, 3, 4, or 5). No words, no punctuation, no explanation.
            Be consistent: the same answer for the same question must always get the same score.
            """
                .trimIndent()
    }
}

/**
 * judge 채점 결과.
 *
 * @property score 0.0~1.0로 정규화한 정답성 점수(표본 median을 `(median−1)/4`로 매핑).
 * @property reasoning median·표본·표준편차·degenerate 요약.
 * @property samples 디코드별 1~5 정수 점수(점수 토큰이 없던 디코드는 1).
 * @property sampleStdDev 표본 모표준편차.
 * @property degenerate 어떤 디코드라도 1~5 토큰을 못 내 1로 처리됐으면 true.
 */
data class JudgeVerdict(
    val score: Double,
    val reasoning: String,
    val samples: List<Int> = emptyList(),
    val sampleStdDev: Double = 0.0,
    val degenerate: Boolean = false,
)
