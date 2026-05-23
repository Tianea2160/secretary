package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.tianea.secretary.core.agent.knowhow.KnowHowStore
import org.tianea.secretary.core.scheduling.ChatContext

/**
 * `retrieveKnowHow` → `callLLM` 간 데이터 캐리어.
 *
 * @property originalInput 사용자가 보낸 원본 입력 텍스트.
 * @property knowHowBlock 채택된 노하우를 마크다운 텍스트로 합친 블록. 노하우가 없거나 비활성 시 null.
 * @property adoptedIds 프롬프트에 주입된 노하우의 ID 목록. `touchKnowHow` 노드가 LLM 호출 성공
 *   직후 [KnowHowStore.touch]를 호출할 때 사용한다. 비활성/공집합 경로에서는 비어 있다.
 */
data class KnowHowWithInput(
    val originalInput: String,
    val knowHowBlock: String?,
    val adoptedIds: List<String>,
)

private val retrieveLog = LoggerFactory.getLogger("org.tianea.secretary.core.agent.graph.nodes.retrieveKnowHow")

/**
 * `retrieveKnowHow` 노드 정의.
 *
 * 입력 텍스트로 코사인 유사도 검색 → 재랭킹 → 토큰 예산 필터링을 거쳐 노하우 블록을 만든다.
 * `touch`는 이 단계에서 호출하지 않는다 — LLM 호출이 실패하면 노하우는 실제로 사용된 것이 아니므로
 * 사용 기록이 남으면 안 된다. 대신 채택된 ID를 캐리어에 실어 [touchKnowHowNodeDelegate]가
 * callLLM 성공 후에 일괄 갱신한다.
 *
 * `enabled=false`, [ChatContext]에 `chatId`가 없는 경우, 또는 검색·재랭킹 도중 오류가 발생하면
 * 노하우 없이 통과시킨다 — 일시적 인프라 장애가 사용자 응답 전체를 막아서는 안 된다는 원칙.
 *
 * @param knowHowStore 검색·재랭킹·예산 적용을 노출하는 저장소.
 * @param enabled 노하우 파이프라인 전체 on/off 스위치.
 * @param topK 유사도 검색 상위 K.
 * @param tokenBudget 채택 노하우 합산 근사 토큰 상한.
 */
fun retrieveKnowHowNodeDelegate(
    knowHowStore: KnowHowStore,
    enabled: Boolean,
    topK: Int,
    tokenBudget: Int,
): AIAgentNodeDelegate<String, KnowHowWithInput> =
    node("retrieveKnowHow") { input ->
        val passthrough = KnowHowWithInput(originalInput = input, knowHowBlock = null, adoptedIds = emptyList())
        if (!enabled) {
            return@node passthrough
        }
        val chatId =
            currentCoroutineContext()[ChatContext]?.chatId
                ?: return@node passthrough

        runCatching {
            val candidates = knowHowStore.search(chatId, input, topK)
            val reranked = knowHowStore.rerank(candidates)
            val adopted = knowHowStore.applyTokenBudget(reranked, tokenBudget)

            if (adopted.isEmpty()) {
                passthrough
            } else {
                val block =
                    buildString {
                        appendLine("## 관련 노하우")
                        adopted.forEachIndexed { idx, scored ->
                            appendLine("### ${idx + 1}. ${scored.knowHow.intent}")
                            appendLine(scored.knowHow.body)
                            if (idx < adopted.lastIndex) appendLine()
                        }
                    }
                KnowHowWithInput(
                    originalInput = input,
                    knowHowBlock = block,
                    adoptedIds = adopted.map { it.knowHow.id },
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            retrieveLog.warn("노하우 검색 실패 — 노하우 없이 진행 [chatId={}]: {}", chatId, error.message)
            passthrough
        }
    }
