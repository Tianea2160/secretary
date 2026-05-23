package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import kotlinx.coroutines.currentCoroutineContext
import org.tianea.secretary.core.scheduling.ChatContext
import org.tianea.secretary.telegram.TelegramReactionSender

/**
 * `reactEnd` 노드 정의.
 *
 * 정상 종료 경로에서 `reactStart`가 부착한 "처리 중" 리액션을 제거한다.
 * `messageId`가 없으면 no-op. 출력은 그대로 통과시켜 `nodeFinish`로 흘려보낸다.
 *
 * **예외 종료 경로**에서는 이 노드에 도달하지 못하므로, `AssistantAgentFactory`의 `EventHandler`
 * (`onAgentExecutionFailed`)가 표식 제거를 책임진다. 두 경로가 각각 다른 노드/핸들러를 통해
 * 표식 제거를 보장한다.
 *
 * @param reactionSender 리액션 제거에 사용하는 텔레그램 클라이언트 래퍼.
 */
fun reactEndNodeDelegate(reactionSender: TelegramReactionSender): AIAgentNodeDelegate<String, String> =
    node("reactEnd") { output ->
        currentCoroutineContext()[ChatContext]?.let { ctx ->
            ctx.messageId?.let { messageId -> reactionSender.clearProcessing(ctx.chatId, messageId) }
        }
        output
    }
