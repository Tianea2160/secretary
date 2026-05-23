package org.tianea.secretary.core.agent.knowhow

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import io.hypersistence.tsid.TSID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 노하우 후보 목록을 기존 저장소와 비교해 ADD / UPDATE / SKIP 판정 후 반영한다.
 *
 * Mem0 방식: 각 후보에 대해 유사 노하우를 벡터 검색하고, LLM이 중복 여부를 판정한다.
 * - ADD  : 유사 항목 없음 → 신규 저장
 * - UPDATE : 유사 항목 있지만 본 후보가 더 낫거나 보완 가능 → 기존 항목 갱신
 * - SKIP  : 기존 항목과 완전 중복이거나 저장할 가치 없음 → 아무 작업 안 함
 *
 * [PromptExecutor]로 대화 세션과 무관한 detached LLM 호출을 수행하므로
 * [ChatMemory]나 세션 프롬프트를 오염시키지 않는다.
 *
 * @param promptExecutor Koog `PromptExecutor` — 독립 LLM 호출에 사용.
 * @param model 호출에 사용할 LLM 모델.
 * @param store 노하우 저장소 — 유사도 검색·저장·갱신에 사용.
 */
class KnowHowConsolidator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val store: KnowHowStore,
) {
    private val log = LoggerFactory.getLogger(KnowHowConsolidator::class.java)

    /**
     * 후보 목록 각각에 대해 ADD/UPDATE/SKIP 판정 후 [KnowHowStore]에 반영한다.
     *
     * 각 후보 처리 중 오류가 발생해도 다음 후보 처리를 계속하며,
     * 전체 실패 시에도 예외를 전파하지 않는다 (호출부인 그래프 노드가 격리 담당).
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
            runCatching {
                processCandidate(candidate, chatId, sourceSessionId)
            }.onFailure { error ->
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

    private fun addNew(
        candidate: KnowHowCandidate,
        chatId: Long,
        sourceSessionId: String,
    ) {
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
                "ID: ${kh.id}\n의도: ${kh.intent}\n본문: ${kh.body}\n중요도: ${kh.importance}"
            }

        val judgmentPrompt =
            prompt("know-how-consolidate") {
                system(CONSOLIDATE_SYSTEM_PROMPT)
                user(
                    buildString {
                        appendLine("## 새 후보")
                        appendLine("의도: ${candidate.intent}")
                        appendLine("본문: ${candidate.body}")
                        appendLine("중요도: ${candidate.importance}")
                        appendLine()
                        appendLine("## 유사 기존 노하우")
                        appendLine(similarText)
                    },
                )
            }

        val result =
            promptExecutor.executeStructured<ConsolidationVerdict>(
                prompt = judgmentPrompt,
                model = model,
                fixingParser = StructureFixingParser(model = model, retries = STRUCTURE_FIX_RETRIES),
            )

        val structuredResponse =
            result.getOrElse { error ->
                log.warn("ADD/UPDATE/SKIP 판정 파싱 실패: {} — SKIP으로 기본 처리(중복 폭주 방지)", error.message)
                return ConsolidationVerdict(action = ConsolidationAction.SKIP)
            }
        return structuredResponse.data
    }

    private companion object {
        const val TOP_K_FOR_DEDUP = 3

        /** 구조화 응답 파싱 실패 시 보조 LLM으로 교정을 시도하는 최대 재시도 횟수. */
        const val STRUCTURE_FIX_RETRIES = 2

        val CONSOLIDATE_SYSTEM_PROMPT =
            """
            당신은 새로운 노하우 후보와 기존 노하우 목록을 비교해 처리 방법을 결정하는 전문가입니다.

            다음 중 하나를 결정하세요:
            - ADD   : 기존 노하우와 충분히 다르므로 새로 추가해야 한다
            - UPDATE : 기존 노하우 중 하나와 유사하지만 새 후보가 더 풍부하거나 보완 가능 → 기존 항목을 개선한다
            - SKIP  : 기존 노하우와 내용이 중복되거나 저장 가치가 없다

            UPDATE를 선택할 때:
            - `targetId`: 갱신할 기존 노하우의 ID
            - `mergedIntent`: 통합된 의도 한 줄 (변경 없으면 기존 intent 유지)
            - `mergedBody`: 통합된 본문 (두 내용을 병합하거나 개선)

            ADD 또는 SKIP을 선택할 때는 `targetId`, `mergedIntent`, `mergedBody`를 null로 두세요.
            """.trimIndent()
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

/**
 * LLM이 결정하는 노하우 중복 판정 액션.
 */
@Serializable
enum class ConsolidationAction {
    /** 기존과 충분히 다르므로 신규 저장. */
    ADD,

    /** 기존 항목을 새 후보로 보완·개선. */
    UPDATE,

    /** 중복이거나 저장 가치 없음 — 아무 작업 안 함. */
    SKIP,
}
