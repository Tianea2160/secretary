package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import kotlinx.coroutines.currentCoroutineContext
import org.tianea.secretary.core.scheduling.ChatContext
import org.tianea.secretary.telegram.TelegramReactionSender

/**
 * `reactStart` 노드 정의.
 *
 * [ChatContext]의 `messageId`가 있을 때만 텔레그램 메시지에 "처리 중" 리액션을 부착한다. 스케줄러 경로처럼 `messageId`가 없으면 no-op.
 * 입력은 그대로 통과시켜 다음 노드로 전달한다.
 *
 * 표식의 **제거**는 정상 종료 시 [reactEndNodeDelegate], 예외 종료 시 `AssistantAgentFactory`의 `EventHandler`가 나눠
 * 맡는다. 이 노드는 부착만 책임진다.
 *
 * `by` 위임용 [AIAgentNodeDelegate]를 반환하므로 `val reactStart by reactStartNodeDelegate(...)`로 strategy
 * 블록에서 등록한다.
 *
 * @param reactionSender 리액션 부착에 사용하는 텔레그램 클라이언트 래퍼.
 */
fun reactStartNodeDelegate(
    reactionSender: TelegramReactionSender
): AIAgentNodeDelegate<String, String> =
    node("reactStart") { input ->
        currentCoroutineContext()[ChatContext]?.let { ctx ->
            ctx.messageId?.let { messageId -> reactionSender.setProcessing(ctx.chatId, messageId) }
        }
        input
    }
