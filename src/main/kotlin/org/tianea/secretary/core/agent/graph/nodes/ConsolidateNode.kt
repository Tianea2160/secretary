package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.tianea.secretary.core.agent.knowhow.KnowHowConsolidator
import org.tianea.secretary.core.scheduling.ChatContext

private val consolidateLog = LoggerFactory.getLogger("org.tianea.secretary.core.agent.graph.nodes.consolidate")

/**
 * `consolidate` 노드 정의.
 *
 * `reflect`가 추출한 후보를 기존 노하우와 비교해 ADD/UPDATE/SKIP 판정 후 `know_how` 테이블에 반영한다.
 * 후보가 비었거나 `enabled=false`거나 [ChatContext]가 없으면 즉시 응답 텍스트만 반환한다.
 *
 * `CancellationException`은 그대로 전파해 structured concurrency를 유지하고, 그 외 예외는 격리한다 —
 * consolidate 실패가 응답 전달을 막아서는 안 된다. 다만 silent swallowing은 운영 가시성을 해치므로
 * `.onFailure`에서 경고 로그를 남긴다.
 *
 * @param knowHowConsolidator ADD/UPDATE/SKIP 판정 + 반영 책임자.
 * @param enabled 노하우 파이프라인 on/off 스위치.
 */
fun consolidateNodeDelegate(
    knowHowConsolidator: KnowHowConsolidator,
    enabled: Boolean,
): AIAgentNodeDelegate<ReflectOutput, String> =
    node("consolidate") { output ->
        if (!enabled || output.candidates.isEmpty()) {
            return@node output.responseText
        }
        val chatContext =
            currentCoroutineContext()[ChatContext]
                ?: return@node output.responseText

        runCatching {
            knowHowConsolidator.consolidate(
                candidates = output.candidates,
                chatId = chatContext.chatId,
                sourceSessionId = chatContext.sessionId,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            consolidateLog.warn(
                "노하우 consolidate 실패 [chatId={}, session={}, candidates={}]: {}",
                chatContext.chatId,
                chatContext.sessionId,
                output.candidates.size,
                error.message,
            )
        }
        output.responseText
    }
