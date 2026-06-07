package org.tianea.secretary.core.agent.knowhow

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.fasterxml.jackson.databind.ObjectMapper
import io.hypersistence.tsid.TSID
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.ai.converter.BeanOutputConverter

/**
 * 노하우 후보 목록을 기존 저장소와 비교해 ADD / UPDATE / SKIP 판정 후 반영한다.
 *
 * Mem0 방식: 각 후보에 대해 유사 노하우를 벡터 검색하고, LLM이 중복 여부를 판정한다.
 * - ADD : 유사 항목 없음 → 신규 저장
 * - UPDATE : 유사 항목 있지만 본 후보가 더 낫거나 보완 가능 → 기존 항목 갱신
 * - SKIP : 기존 항목과 완전 중복이거나 저장할 가치 없음 → 아무 작업 안 함
 *
 * [PromptExecutor]로 대화 세션과 무관한 detached LLM 호출을 수행하므로 [ChatMemory]나 세션 프롬프트를 오염시키지 않는다.
 *
 * @param promptExecutor Koog `PromptExecutor` — 독립 LLM 호출에 사용.
 * @param model 호출에 사용할 LLM 모델.
 * @param store 노하우 저장소 — 유사도 검색·저장·갱신에 사용.
 * @param objectMapper Kotlin 모듈이 등록된 애플리케이션 [ObjectMapper] 빈 — 구조화 출력 스키마 생성·파싱에 사용.
 */
class KnowHowConsolidator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val store: KnowHowStore,
    objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(KnowHowConsolidator::class.java)

    /** [ConsolidationVerdict] 타입에서 JSON 스키마·포맷 지시문을 자동 생성하고 응답을 파싱하는 Spring AI 구조화 출력 변환기. */
    private val verdictConverter =
        BeanOutputConverter(ConsolidationVerdict::class.java, objectMapper)

    /**
     * 후보 목록 각각에 대해 ADD/UPDATE/SKIP 판정 후 [KnowHowStore]에 반영한다.
     *
     * 각 후보 처리 중 오류가 발생해도 다음 후보 처리를 계속하며, 전체 실패 시에도 예외를 전파하지 않는다 (호출부인 그래프 노드가 격리 담당).
     *
     * @param candidates [KnowHowReflector]가 추출한 노하우 후보 목록.
     * @param chatId 사용자 격리 키.
     * @param sourceSessionId 후보를 추출한 대화 세션 ID.
     */
    suspend fun consolidate(
        candidates: List<KnowHowCandidate>,
        chatId: Long,
        sourceSessionId: String,
    ) {
        for (candidate in candidates) {
            runCatching { processCandidate(candidate, chatId, sourceSessionId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn(
                        "노하우 후보 처리 실패 [chatId={}, session={}, intent={}]: {}",
                        chatId,
                        sourceSessionId,
                        candidate.intent,
                        error.message,
                    )
                }
        }
    }

    private suspend fun processCandidate(
        candidate: KnowHowCandidate,
        chatId: Long,
        sourceSessionId: String,
    ) {
        val similar = store.searchSimilar(chatId, candidate.intent, topK = TOP_K_FOR_DEDUP)

        if (similar.isEmpty()) {
            addNew(candidate, chatId, sourceSessionId)
            return
        }

        val verdict = judgeVerdict(candidate, similar)

        when (verdict.action) {
            ConsolidationAction.ADD -> {
                addNew(candidate, chatId, sourceSessionId)
            }

            ConsolidationAction.UPDATE -> {
                val targetId = verdict.targetId
                val target = similar.firstOrNull { it.knowHow.id == targetId }
                if (target != null) {
                    val existing = target.knowHow
                    store.update(
                        id = existing.id,
                        intent = verdict.mergedIntent ?: existing.intent,
                        body = verdict.mergedBody ?: existing.body,
                        importance = candidate.importance.coerceAtLeast(existing.importance),
                    )
                    log.debug("노하우 갱신 [id={}, chatId={}]", existing.id, chatId)
                } else {
                    log.warn(
                        "UPDATE 판정이지만 targetId가 similar 후보 목록에 없음 — SKIP [chatId={}, targetId={}, intent={}]",
                        chatId,
                        targetId,
                        candidate.intent,
                    )
                }
            }

            ConsolidationAction.SKIP -> {
                log.debug("노하우 SKIP [chatId={}, intent={}]", chatId, candidate.intent)
            }
        }
    }

    private fun addNew(candidate: KnowHowCandidate, chatId: Long, sourceSessionId: String) {
        val now = Instant.now()
        val knowHow =
            KnowHow(
                id = TSID.fast().toString(),
                chatId = chatId,
                intent = candidate.intent,
                body = candidate.body,
                importance = candidate.importance,
                useCount = 0,
                createdAt = now,
                updatedAt = now,
                lastUsedAt = null,
                sourceSessionId = sourceSessionId,
            )
        store.save(knowHow)
        log.debug("노하우 추가 [id={}, chatId={}, intent={}]", knowHow.id, chatId, candidate.intent)
    }

    private suspend fun judgeVerdict(
        candidate: KnowHowCandidate,
        similar: List<ScoredKnowHow>,
    ): ConsolidationVerdict {
        val similarText =
            similar.joinToString("\n\n") { scored ->
                val kh = scored.knowHow
                "ID: ${kh.id}\nintent: ${kh.intent}\nbody: ${kh.body}\nimportance: ${kh.importance}"
            }

        val judgmentPrompt =
            prompt("know-how-consolidate") {
                system(CONSOLIDATE_SYSTEM_PROMPT + "\n\n" + verdictConverter.format)
                user(
                    buildString {
                        appendLine("## New candidate")
                        appendLine("intent: ${candidate.intent}")
                        appendLine("body: ${candidate.body}")
                        appendLine("importance: ${candidate.importance}")
                        appendLine()
                        appendLine("## Similar existing know-how")
                        appendLine(similarText)
                        appendLine()
                        appendLine("/no_think")
                    }
                )
            }

        return runCatching {
                val responseText =
                    promptExecutor.execute(judgmentPrompt, model, emptyList()).textContent()
                verdictConverter.convert(responseText)
            }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                log.warn("ADD/UPDATE/SKIP 판정 실패: {} — SKIP으로 기본 처리(중복 폭주 방지)", error.message)
                ConsolidationVerdict(action = ConsolidationAction.SKIP)
            }
    }

    private companion object {
        const val TOP_K_FOR_DEDUP = 3

        val CONSOLIDATE_SYSTEM_PROMPT =
            """
            You are an expert that compares a new know-how candidate against existing know-how entries and decides how to handle it.

            Choose exactly one action:
            - ADD    : the candidate is sufficiently different from all existing entries → store it as a new entry
            - UPDATE : the candidate is similar to one of the existing entries but is richer or complements it → improve the existing entry
            - SKIP   : the candidate duplicates an existing entry or is not worth storing

            When choosing UPDATE:
            - `targetId`: the ID of the existing know-how to update
            - `mergedIntent`: the merged intent line (keep the existing intent if no change is needed)
            - `mergedBody`: the merged body (combine or improve the two)

            When choosing ADD or SKIP, leave `targetId`, `mergedIntent`, and `mergedBody` as null.
            """
                .trimIndent()
    }
}

/**
 * consolidate 판정 결과.
 *
 * @property action LLM이 내린 처리 방법: ADD / UPDATE / SKIP.
 * @property targetId UPDATE 시 갱신할 기존 노하우 ID. ADD·SKIP이면 null.
 * @property mergedIntent UPDATE 시 통합된 intent 텍스트. null이면 후보의 intent 그대로 사용.
 * @property mergedBody UPDATE 시 통합된 body 텍스트. null이면 후보의 body 그대로 사용.
 */
@Serializable
data class ConsolidationVerdict(
    val action: ConsolidationAction,
    val targetId: String? = null,
    val mergedIntent: String? = null,
    val mergedBody: String? = null,
)

/** LLM이 결정하는 노하우 중복 판정 액션. */
@Serializable
enum class ConsolidationAction {
    /** 기존과 충분히 다르므로 신규 저장. */
    ADD,

    /** 기존 항목을 새 후보로 보완·개선. */
    UPDATE,

    /** 중복이거나 저장 가치 없음 — 아무 작업 안 함. */
    SKIP,
}
