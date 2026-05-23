package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.prompt.message.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.tianea.secretary.core.agent.knowhow.KnowHowCandidate
import org.tianea.secretary.core.agent.knowhow.KnowHowReflector
import org.tianea.secretary.core.scheduling.ChatContext

private val reflectLog = LoggerFactory.getLogger("org.tianea.secretary.core.agent.graph.nodes.reflect")

/**
 * `reflect` → `consolidate` 간 데이터 캐리어.
 *
 * @property responseText 이번 턴의 어시스턴트 응답 텍스트 — 최종 그래프 출력으로 그대로 흘러간다.
 * @property candidates [KnowHowReflector]가 추출한 노하우 후보 목록. 비어 있으면 `consolidate`는 no-op.
 */
data class ReflectOutput(
    val responseText: String,
    val candidates: List<KnowHowCandidate>,
)

/**
 * `reflect` 노드 정의.
 *
 * 이번 턴의 user/assistant 메시지를 [KnowHowReflector]에 넘겨 재사용 가능한 노하우 후보를 추출한다.
 * `enabled=false` 또는 [ChatContext] 미존재 시 빈 후보 리스트로 즉시 통과시켜 응답 텍스트만 흘려보낸다.
 *
 * 모든 예외는 격리한다 — reflect 실패가 응답 전달을 막아서는 안 된다.
 * 응답 텍스트는 어떤 경로에서도 그대로 보존된다.
 *
 * @param knowHowReflector LLM 기반 후보 추출기.
 * @param enabled 노하우 파이프라인 on/off 스위치.
 */
fun reflectNodeDelegate(
    knowHowReflector: KnowHowReflector,
    enabled: Boolean,
): AIAgentNodeDelegate<String, ReflectOutput> =
    node("reflect") { responseText ->
        if (!enabled) {
            return@node ReflectOutput(responseText = responseText, candidates = emptyList())
        }
        val chatContext =
            currentCoroutineContext()[ChatContext]
                ?: return@node ReflectOutput(responseText = responseText, candidates = emptyList())

        runCatching {
            val messages = llm.prompt.messages
            val lastUserMsg = messages.lastOrNull { it is Message.User }?.content ?: ""
            val candidates =
                knowHowReflector.reflect(
                    userText = lastUserMsg,
                    assistantText = responseText,
                    sourceSessionId = chatContext.sessionId,
                    chatId = chatContext.chatId,
                )
            ReflectOutput(responseText = responseText, candidates = candidates)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            reflectLog.warn(
                "노하우 reflect 실패 [chatId={}, session={}]: {}",
                chatContext.chatId,
                chatContext.sessionId,
                error.message,
            )
            ReflectOutput(responseText = responseText, candidates = emptyList())
        }
    }
