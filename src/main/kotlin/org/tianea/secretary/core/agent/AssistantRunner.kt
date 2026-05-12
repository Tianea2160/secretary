package org.tianea.secretary.core.agent

import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component
import org.tianea.secretary.core.scheduling.ChatContext

/**
 * 호출마다 [AssistantAgentFactory]로 새 agent 인스턴스를 만들어 실행하는 단일 진입점.
 *
 * 단일 agent 인스턴스를 공유하면 Koog `OpenTelemetry` feature의 `SpanCollector`가 동시 호출
 * 사이에 span tree를 꼬아 `IllegalStateException: Error deleting span node`로 터진다.
 * 락으로 직렬화하면 race는 막을 수 있지만 호출 시간이 긴 모델(qwen3.6:latest 등)에서는 후속
 * 트리거가 큐에 쌓여 처리 지연이 누적된다. 인스턴스를 호출마다 새로 만들면 span tree가 격리되어
 * 동시 실행에 안전해진다.
 */
@Component
class AssistantRunner(
    private val factory: AssistantAgentFactory,
) {
    fun run(
        chatId: Long,
        sessionId: String,
        prompt: String,
    ): String =
        runBlocking(ChatContext(chatId, sessionId)) {
            factory.create().run(prompt, sessionId)
        }
}
