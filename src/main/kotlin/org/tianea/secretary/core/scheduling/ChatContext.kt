package org.tianea.secretary.core.scheduling

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 현재 처리 중인 텔레그램 chat을 코루틴 컨텍스트로 전파.
 *
 * Koog 도구·그래프 노드·feature 핸들러는 모두 agent 코루틴 안에서 실행되므로 `currentCoroutineContext()[ChatContext]`로 어느
 * chat의 어느 메시지에 대한 요청인지 식별한다. `AssistantRunner`가 agent 호출 전 주입한다.
 *
 * @property messageId 처리중 표식을 부착할 사용자 메시지. 촉발 메시지가 없는 스케줄러 경로는 `null`.
 */
data class ChatContext(val chatId: Long, val sessionId: String, val messageId: Int?) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChatContext>
}
