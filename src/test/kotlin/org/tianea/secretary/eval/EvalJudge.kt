package org.tianea.secretary.eval

import kotlin.math.exp

/**
 * G-Eval(Liu et al., 2023, [arXiv:2303.16634](https://arxiv.org/abs/2303.16634)) 방식의 logprob 가중
 * LLM-as-a-judge.
 *
 * judge에게 기준답안 대비 정답성을 **1~5 정수 한 글자**로만 출력하게 하고, 그 점수 토큰의 `top_logprobs` 분포로 확률 가중합 `score = Σ
 * p(sᵢ)·sᵢ / Σ p(sᵢ)`(1~5 토큰 위에서 재정규화)를 계산한다. 정수 한 칸 단위의 거친 점수 대신 연속값을 얻어 judge 점수의 변별력을 높이는 것이
 * 목적(G-Eval §3.4 "Scoring Function").
 *
 * Ollama는 logprobs를 주지만 Koog `PromptExecutor`(Spring AI 브릿지)가 이를 응답 객체·trace 양쪽에서 버리므로
 * (`KoogLogprobsProbe`로 실측), Koog가 아니라 [OllamaChatClient]로 Ollama `/api/chat`를 직접 호출한다.
 *
 * 회귀 게이트·Langfuse `NUMERIC` score의 기존 의미(0~1, threshold 0.7)를 보존하려고 1~5 가중점수를 `(w−1)/4`로 0~1로 정규화해
 * [JudgeVerdict.score]에 담는다. judge 흔들림이 모델 회귀로 오인되면 안 되므로 `temperature=0`으로 고정한다 ([OllamaOptions]).
 *
 * @param ollama logprobs를 받기 위한 Ollama 직접 호출 클라이언트.
 * @param model 채점에 쓸 Ollama 모델 id. 응답 모델과 분리해 고정하는 것을 권장.
 */
class EvalJudge(private val ollama: OllamaChatClient, private val model: String) {
    /**
     * 한 평가 케이스를 채점한다.
     *
     * @param question 데이터셋 item의 입력.
     * @param referenceAnswer 기대 정답.
     * @param actualOutput 어시스턴트가 실제로 생성한 응답.
     * @return 0.0~1.0로 정규화한 정답성 점수와 logprob 분포 내역. score 토큰을 끝내 못 찾으면 예외를 던진다(평가는 실패를 숨기지 않는다).
     */
    fun judge(question: String, referenceAnswer: String, actualOutput: String): JudgeVerdict {
        val response =
            ollama.chat(
                OllamaChatRequest(
                    model = model,
                    messages =
                        listOf(
                            OllamaMessage(role = "system", content = JUDGE_SYSTEM_PROMPT),
                            OllamaMessage(
                                role = "user",
                                content = buildUserPrompt(question, referenceAnswer, actualOutput),
                            ),
                        ),
                )
            )
        return scoreFromLogprobs(response)
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

    /**
     * G-Eval 확률 가중합. 생성 토큰 중 **첫 1~5 점수 토큰**을 찾고(선행 공백/개행 토큰은 건너뜀), 그 위치의 `top_logprobs`에서 1~5 숫자
     * 후보만 골라 확률(=`exp(logprob)`)로 가중평균한다. `top_logprobs`는 상위 k개만 주므로 1~5 토큰의 확률만으로 재정규화한다. 후보에 숫자가
     * 하나도 없으면 점수 토큰 자체를 확정값으로 쓴다.
     */
    private fun scoreFromLogprobs(response: OllamaChatResponse): JudgeVerdict {
        val scoreToken =
            response.logprobs.firstOrNull { it.token.asScore() != null }
                ?: error(
                    "judge가 1~5 점수 토큰을 내지 않음: content='${response.message.content}', logprobs=${response.logprobs}"
                )

        val probByScore =
            scoreToken.topLogprobs
                .mapNotNull { cand -> cand.token.asScore()?.let { it to exp(cand.logprob) } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, probs) -> probs.sum() }
                .ifEmpty { mapOf(scoreToken.token.asScore()!! to 1.0) }

        val total = probByScore.values.sum()
        val weighted = probByScore.entries.sumOf { (score, prob) -> score * prob } / total
        val normalized = (weighted - 1.0) / 4.0
        return JudgeVerdict(
            score = normalized,
            reasoning = buildReasoning(weighted, probByScore, total),
        )
    }

    private fun buildReasoning(
        weighted: Double,
        probByScore: Map<Int, Double>,
        total: Double,
    ): String {
        val distribution =
            (1..5).joinToString(" ") { score ->
                "%d=%.3f".format(score, (probByScore[score] ?: 0.0) / total)
            }
        return "G-Eval logprob-weighted %.3f/5 (p: %s)".format(weighted, distribution)
    }

    /** 토큰 텍스트가 1~5 점수면 그 정수를, 아니면 null. 선행/후행 공백 토큰을 허용한다. */
    private fun String.asScore(): Int? = trim().toIntOrNull()?.takeIf { it in 1..5 }

    private companion object {
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
 * @property score 0.0~1.0로 정규화한 정답성 점수(1~5 logprob 가중점수를 `(w−1)/4`로 매핑).
 * @property reasoning logprob 분포 내역(가중점수 + 1~5 확률).
 */
data class JudgeVerdict(val score: Double, val reasoning: String)
