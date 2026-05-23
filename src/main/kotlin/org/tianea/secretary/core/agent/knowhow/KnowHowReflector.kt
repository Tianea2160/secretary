package org.tianea.secretary.core.agent.knowhow

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * 이번 대화 턴(사용자 입력 + 어시스턴트 응답)에서 재사용 가능한 절차적 노하우 후보를 추출한다.
 *
 * [PromptExecutor]로 대화 세션과 무관한 **detached LLM 호출**을 수행하므로
 * [ChatMemory]나 세션 프롬프트를 오염시키지 않는다.
 *
 * @param promptExecutor Koog `PromptExecutor` — 독립 LLM 호출에 사용.
 * @param model 호출에 사용할 LLM 모델.
 * @param minImportance 이 값 미만의 [KnowHowCandidate.importance] 후보는 결과에서 제외된다.
 */
class KnowHowReflector(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val minImportance: Double,
) {
    private val log = LoggerFactory.getLogger(KnowHowReflector::class.java)

    /**
     * 이번 턴의 대화를 분석해 재사용 가능한 절차적 노하우 후보 목록을 반환한다.
     *
     * LLM이 반환한 후보 중 [minImportance] 미만인 항목은 버린다.
     * LLM 호출이나 파싱에 실패하면 빈 리스트를 반환한다 (호출부가 예외를 격리하도록 설계).
     *
     * @param userText 이번 턴의 사용자 입력 텍스트.
     * @param assistantText 이번 턴의 어시스턴트 응답 텍스트.
     * @param sourceSessionId 노하우 출처 세션 ID — 저장 시 메타데이터로 기록.
     * @param chatId 사용자 식별자 — 저장 시 격리 키로 사용.
     * @return 중요도 임계치를 통과한 노하우 후보 목록. 실패 시 빈 리스트.
     */
    suspend fun reflect(
        userText: String,
        assistantText: String,
        sourceSessionId: String,
        chatId: Long,
    ): List<KnowHowCandidate> {
        return runCatching {
            val extractionPrompt =
                prompt("know-how-reflect") {
                    system(REFLECT_SYSTEM_PROMPT)
                    user(
                        buildString {
                            appendLine("## 사용자 입력")
                            appendLine(userText)
                            appendLine()
                            appendLine("## 어시스턴트 응답")
                            appendLine(assistantText)
                        },
                    )
                }

            val result =
                promptExecutor.executeStructured<ReflectResponse>(
                    prompt = extractionPrompt,
                    model = model,
                    fixingParser = StructureFixingParser(model = model, retries = STRUCTURE_FIX_RETRIES),
                )

            val structuredResponse =
                result.getOrElse { error ->
                    if (error is CancellationException) throw error
                    log.warn("노하우 추출 구조화 파싱 실패 [chatId={}, session={}]: {}", chatId, sourceSessionId, error.message)
                    return emptyList()
                }
            structuredResponse.data.candidates.filter { it.importance >= minImportance }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            log.warn("노하우 추출 LLM 호출 실패 [chatId={}, session={}]: {}", chatId, sourceSessionId, error.message)
            emptyList()
        }
    }

    private companion object {
        /** 구조화 응답 파싱 실패 시 보조 LLM으로 교정을 시도하는 최대 재시도 횟수. */
        const val STRUCTURE_FIX_RETRIES = 2

        val REFLECT_SYSTEM_PROMPT =
            """
            당신은 대화 내용에서 **재사용 가능한 절차적 노하우**를 추출하는 전문가입니다.

            절차적 노하우란 "이런 상황에서는 이런 방식으로 접근하면 된다"는 방법론입니다.
            - 단순한 사실 정보나 사용자 개인 선호가 아닌, **미래에도 유용한 작업 절차·접근법**만 추출하세요.
            - 각 후보는 독립적으로 이해 가능해야 합니다.
            - 해당 대화 턴에서 추출할 만한 노하우가 없다면 후보를 비워 두세요.

            각 후보에 대해:
            - `intent`: "언제/어떤 상황에 이 노하우를 쓰는가"를 한 줄로 요약 (한국어, 50자 이내)
            - `body`: 구체적인 절차·방법론 본문 (한국어, 500자 이내)
            - `importance`: 이 노하우의 미래 재사용 가치 (0.0 ~ 1.0 실수)
              - 0.9~1.0: 범용적이고 반복 적용 가능한 핵심 방법론
              - 0.7~0.8: 특정 영역에서 유용한 접근법
              - 0.5~0.6: 제한적 상황에서만 유용
              - 0.5 미만: 일회성 또는 재사용 가치 낮음
            """.trimIndent()
    }
}

/**
 * LLM이 reflect 호출에서 반환하는 구조화 응답 래퍼.
 *
 * [candidates]는 이번 턴에서 추출된 노하우 후보 목록이다.
 * 추출할 노하우가 없으면 빈 리스트가 반환된다.
 */
@Serializable
data class ReflectResponse(
    val candidates: List<KnowHowCandidate> = emptyList(),
)

/**
 * LLM이 대화 턴 분석으로 추출한 절차적 노하우 후보.
 *
 * [KnowHowConsolidator]가 기존 노하우와 비교해 ADD/UPDATE/SKIP을 결정한다.
 *
 * @property intent "언제/어떤 상황에 이 노하우를 쓰는가"를 한 줄로 요약한 텍스트.
 *   임베딩 검색의 기준 키로도 사용된다.
 * @property body 구체적인 절차·방법론 본문.
 * @property importance LLM이 추정한 미래 재사용 가치 (0.0 ~ 1.0).
 */
@Serializable
data class KnowHowCandidate(
    val intent: String,
    val body: String,
    val importance: Double,
)
