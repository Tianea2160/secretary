package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.prompt.message.Message
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.tianea.secretary.core.agent.knowhow.KnowHowStore

private val touchLog = LoggerFactory.getLogger("org.tianea.secretary.core.agent.graph.nodes.touchKnowHow")

/**
 * `touchKnowHow` 노드 정의.
 *
 * `callLLM`이 성공한 직후에만 [KnowHowStore.touch]를 호출해, 프롬프트에 주입됐지만 실제로는 응답에
 * 기여하지 못한 노하우(LLM 호출 실패 등)가 사용 카운트·recency를 부풀리지 못하도록 한다.
 *
 * touch 자체가 실패해도 응답 전달은 막지 않는다 — `use_count`/`last_used_at`은 통계 가중치라
 * 일시적 손실이 사용자 경험을 해치는 것보다 가볍다.
 *
 * @param knowHowStore 사용 카운트·last_used_at을 갱신하는 저장소.
 */
fun touchKnowHowNodeDelegate(knowHowStore: KnowHowStore): AIAgentNodeDelegate<LlmCallResult, Message.Response> =
    node("touchKnowHow") { carrier ->
        for (id in carrier.adoptedIds) {
            runCatching { knowHowStore.touch(id) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    touchLog.warn("노하우 touch 실패 [id={}]: {}", id, error.message)
                }
        }
        carrier.response
    }
