package org.tianea.secretary.core.agent.knowhow

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.ai.converter.BeanOutputConverter

/**
 * 이번 대화 턴(사용자 입력 + 어시스턴트 응답)에서 재사용 가능한 절차적 노하우 후보를 추출한다.
 *
 * [PromptExecutor]로 대화 세션과 무관한 **detached LLM 호출**을 수행하므로 [ChatMemory]나 세션 프롬프트를 오염시키지 않는다.
 *
 * @param promptExecutor Koog `PromptExecutor` — 독립 LLM 호출에 사용.
 * @param model 호출에 사용할 LLM 모델.
 * @param minImportance 이 값 미만의 [KnowHowCandidate.importance] 후보는 결과에서 제외된다.
 * @param objectMapper Kotlin 모듈이 등록된 애플리케이션 [ObjectMapper] 빈 — 구조화 출력 스키마 생성·파싱에 사용.
 */
class KnowHowReflector(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val minImportance: Double,
    objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(KnowHowReflector::class.java)

    /** [ReflectResponse] 타입에서 JSON 스키마·포맷 지시문을 자동 생성하고 응답을 파싱하는 Spring AI 구조화 출력 변환기. */
    private val outputConverter = BeanOutputConverter(ReflectResponse::class.java, objectMapper)

    /**
     * 이번 턴의 대화를 분석해 재사용 가능한 절차적 노하우 후보 목록을 반환한다.
     *
     * LLM이 반환한 후보 중 [minImportance] 미만인 항목은 버린다. LLM 호출이나 파싱에 실패하면 빈 리스트를 반환한다 (호출부가 예외를 격리하도록 설계).
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
                        system(REFLECT_SYSTEM_PROMPT + "\n\n" + outputConverter.format)
                        user(
                            buildString {
                                appendLine("## User input")
                                appendLine(userText)
                                appendLine()
                                appendLine("## Assistant response")
                                appendLine(assistantText)
                                appendLine()
                                appendLine("/no_think")
                            }
                        )
                    }

                val responseText =
                    promptExecutor.execute(extractionPrompt, model, emptyList()).textContent()
                outputConverter.convert(responseText).candidates.filter {
                    it.importance >= minImportance
                }
            }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                log.warn(
                    "노하우 추출 실패 [chatId={}, session={}]: {}",
                    chatId,
                    sourceSessionId,
                    error.message,
                )
                emptyList()
            }
    }

    private companion object {
        val REFLECT_SYSTEM_PROMPT =
            """
            You are an expert at extracting **reusable procedural know-how** from conversations.

            Procedural know-how is a methodology of the form "in this kind of situation, this approach works".
            - Extract only **procedures or approaches that will remain useful in the future** — not raw facts or personal user preferences.
            - Each candidate must be independently understandable.

            Output shape (MANDATORY):
            - Always return a single JSON **object** with one key `candidates` whose value is the array of extracted candidates.
            - Never output a bare array. Even when there is nothing to extract, return `{"candidates": []}`, not `[]`.

            For each candidate inside the `candidates` array:
            - `intent`: a one-line summary of "when/in what situation this know-how applies" (match the conversation language, ≤ 50 chars)
            - `body`: the concrete procedure or methodology (match the conversation language, ≤ 500 chars)
            - `importance`: future reusability of this know-how (real number 0.0–1.0)
              - 0.9–1.0: generic, broadly reusable core methodology
              - 0.7–0.8: useful approach within a specific domain
              - 0.5–0.6: useful only in restricted situations
              - below 0.5: one-off or low reuse value
            """
                .trimIndent()
    }
}

/**
 * LLM이 reflect 호출에서 반환하는 구조화 응답 래퍼.
 *
 * [candidates]는 이번 턴에서 추출된 노하우 후보 목록이다. 추출할 노하우가 없으면 빈 리스트가 반환된다.
 */
@Serializable data class ReflectResponse(val candidates: List<KnowHowCandidate> = emptyList())

/**
 * LLM이 대화 턴 분석으로 추출한 절차적 노하우 후보.
 *
 * [KnowHowConsolidator]가 기존 노하우와 비교해 ADD/UPDATE/SKIP을 결정한다.
 *
 * @property intent "언제/어떤 상황에 이 노하우를 쓰는가"를 한 줄로 요약한 텍스트. 임베딩 검색의 기준 키로도 사용된다.
 * @property body 구체적인 절차·방법론 본문.
 * @property importance LLM이 추정한 미래 재사용 가치 (0.0 ~ 1.0).
 */
@Serializable
data class KnowHowCandidate(val intent: String, val body: String, val importance: Double)
