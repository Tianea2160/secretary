package org.tianea.secretary.core.agent.graph

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.prompt.message.Message
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.tianea.secretary.core.scheduling.ChatContext
import org.tianea.secretary.telegram.TelegramReactionSender

/**
 * 비서의 chat-only Koog `strategy { }` 그래프를 스프링 빈으로 제공하는 설정.
 *
 * `AIAgentGraphStrategy`는 실행 상태가 없는 그래프 청사진이라 모든 agent 인스턴스가 안전하게
 * 공유할 수 있다 → 싱글턴 빈으로 한 번만 빌드한다. 호출별 데이터(chatId·messageId)는 그래프 빌드가
 * 아니라 노드 실행 시점에 [ChatContext] 코루틴 컨텍스트로 흘러든다.
 */
@Configuration
class ChatStrategyConfig {
    /**
     * 그래프 모양 (선형):
     * `nodeStart → reactStart → preprocess → callLLM → emitText → reactEnd → nodeFinish`
     *
     * 각 노드의 역할:
     * - `reactStart` : [ChatContext]의 messageId가 있으면 처리중 표식(텔레그램 메시지 리액션)을 부착한다 —
     *                  LLM이 이 대화를 처리 중임을 사용자에게 가시화한다. 입력은 그대로 통과.
     * - `preprocess` : 사용자 입력을 trim. 향후 의도 분류·정규화 노드를 끼워 넣을 자리.
     * - `callLLM`    : 내장 [nodeLLMRequest] — 입력 문자열을 user 메시지로 누적해 LLM 호출, 응답 반환.
     * - `emitText`   : [Message.Response]에서 텍스트만 추출해 `AIAgent<String, String>` 출력 타입 유지.
     * - `reactEnd`   : 정상 완료 시 처리중 표식을 제거한다. 출력은 그대로 통과.
     *
     * `messageId`가 없는 스케줄러 경로는 `reactStart`/`reactEnd` 모두 no-op이 된다.
     *
     * 표식 제거의 **실패 경로**는 이 그래프가 아니라 `AssistantAgentFactory`의 `EventHandler`
     * (`onAgentExecutionFailed`)가 담당한다. `callLLM`이 예외로 실패하면 그래프가 거기서 중단되어
     * `reactEnd`에 도달하지 못하므로, 정상 종료는 `reactEnd` 노드가, 예외 종료는 `EventHandler`가
     * 나눠 맡아 두 경로 모두에서 표식 제거를 보장한다.
     *
     * 시스템 프롬프트는 이 그래프가 아니라 `AssistantAgentFactory`의 `AIAgentConfig.withSystemPrompt`로
     * 한 번만 주입한다. `setupPrompt` 노드에서 매 턴 `appendPrompt { system(...) }`를 호출하면
     * `ChatMemory`가 시스템 메시지를 매 턴 누적해 프롬프트가 부풀어 오른다.
     *
     * 다음 단계에서 도구·분기·서브그래프를 추가할 때는 이 그래프에 노드를 더 선언하고 엣지를 잇기만 하면 된다 —
     * 자세한 사용법은 `docs/koog-strategy-graph.md` 참고.
     */
    @Bean
    fun chatStrategy(reactionSender: TelegramReactionSender): AIAgentGraphStrategy<String, String> {
        suspend fun withCurrentMessage(action: (chatId: Long, messageId: Int) -> Unit) {
            currentCoroutineContext()[ChatContext]?.let { ctx ->
                ctx.messageId?.let { action(ctx.chatId, it) }
            }
        }

        return strategy<String, String>("secretary-chat") {
            val reactStart by
                node<String, String>("reactStart") { input ->
                    withCurrentMessage(reactionSender::setProcessing)
                    input
                }
            val preprocess by node<String, String>("preprocess") { input -> input.trim() }
            val callLLM by nodeLLMRequest("callLLM")
            val emitText by node<Message.Response, String>("emitText") { response -> response.content }
            val reactEnd by
                node<String, String>("reactEnd") { output ->
                    withCurrentMessage(reactionSender::clearProcessing)
                    output
                }

            edge(nodeStart forwardTo reactStart)
            edge(reactStart forwardTo preprocess)
            edge(preprocess forwardTo callLLM)
            edge(callLLM forwardTo emitText)
            edge(emitText forwardTo reactEnd)
            edge(reactEnd forwardTo nodeFinish)
        }
    }
}
