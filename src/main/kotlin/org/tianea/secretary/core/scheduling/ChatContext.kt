package org.tianea.secretary.core.scheduling

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 현재 처리 중인 텔레그램 chat을 코루틴 컨텍스트로 전파.
 *
 * Koog 도구는 agent 코루틴 안에서 실행되므로 `currentCoroutineContext()[ChatContext]`로
 * 어느 chat의 요청인지 식별한다. UpdateRouter와 AgentExecutionJob에서 agent 호출 전 주입.
 */
data class ChatContext(
    val chatId: Long,
    val sessionId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChatContext>
}
