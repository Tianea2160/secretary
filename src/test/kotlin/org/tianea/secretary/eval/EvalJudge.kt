package org.tianea.secretary.eval

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.Serializable

/**
 * 기준답안(reference) 대비 어시스턴트 응답을 채점하는 LLM-as-a-judge.
 *
 * 회귀 탐지가 목적이므로 **judge 자체의 흔들림이 모델 회귀로 오인되면 안 된다**. 이를 위해 [LLMParams.temperature]를 0으로 고정하고 구조화
 * 출력([JudgeVerdict])으로 점수 형식을 강제한다. 같은 입력에 대해 judge가 가능한 한 동일한 점수를 내도록 만드는 것이 핵심이다.
 *
 * @param promptExecutor Koog `PromptExecutor` — 대화 세션과 무관한 detached 호출에 사용.
 * @param model 채점에 쓸 LLM 모델. 응답 모델과 분리해 고정하는 것을 권장.
 */
class EvalJudge(private val promptExecutor: PromptExecutor, private val model: LLModel) {
    /**
     * 한 평가 케이스를 채점한다.
     *
     * @param question 데이터셋 item의 입력.
     * @param referenceAnswer 기대 정답.
     * @param actualOutput 어시스턴트가 실제로 생성한 응답.
     * @return 0.0~1.0 점수와 사유. 구조화 파싱이 끝내 실패하면 예외를 던진다(평가는 실패를 숨기지 않는다).
     */
    suspend fun judge(
        question: String,
        referenceAnswer: String,
        actualOutput: String,
    ): JudgeVerdict {
        val judgePrompt =
            prompt("eval-judge", LLMParams(temperature = 0.0)) {
                system(JUDGE_SYSTEM_PROMPT)
                user(
                    buildString {
                        appendLine("## Question")
                        appendLine(question)
                        appendLine()
                        appendLine("## Reference answer")
                        appendLine(referenceAnswer)
                        appendLine()
                        appendLine("## Assistant answer (to grade)")
                        appendLine(actualOutput)
                        appendLine()
                        appendLine("/no_think")
                    }
                )
            }

        return promptExecutor
            .executeStructured<JudgeVerdict>(
                prompt = judgePrompt,
                model = model,
                fixingParser = StructureFixingParser(model = model, retries = STRUCTURE_FIX_RETRIES),
            )
            .getOrThrow()
            .data
    }

    private companion object {
        const val STRUCTURE_FIX_RETRIES = 2

        val JUDGE_SYSTEM_PROMPT =
            """
            You are a strict evaluator grading an assistant's answer against a reference answer.

            Grade ONLY on semantic correctness and completeness relative to the reference — not on
            wording, style, or length. The assistant may phrase things differently; what matters is
            whether it conveys the same correct information the reference does.

            Scoring rubric (`score`, real number 0.0–1.0):
            - 1.0: fully correct and complete; matches the reference's substance.
            - 0.7–0.9: mostly correct, minor omission or imprecision.
            - 0.4–0.6: partially correct; misses or distorts a meaningful part.
            - 0.1–0.3: largely incorrect or off-topic, with a small grain of relevance.
            - 0.0: wrong, empty, or contradicts the reference.

            `reasoning`: one or two sentences justifying the score, citing the specific gap or match.
            Be consistent: the same answer for the same question must always get the same score.
            """
                .trimIndent()
    }
}

/**
 * judge가 반환하는 구조화 채점 결과.
 *
 * @property score 0.0~1.0 정답성 점수.
 * @property reasoning 점수 근거(1~2문장).
 */
@Serializable data class JudgeVerdict(val score: Double, val reasoning: String)
