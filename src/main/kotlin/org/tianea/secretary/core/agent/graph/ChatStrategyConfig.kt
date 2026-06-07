package org.tianea.secretary.core.agent.graph

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.tianea.secretary.core.agent.graph.nodes.callLlmNodeDelegate
import org.tianea.secretary.core.agent.graph.nodes.consolidateNodeDelegate
import org.tianea.secretary.core.agent.graph.nodes.emitTextNodeDelegate
import org.tianea.secretary.core.agent.graph.nodes.preprocessNodeDelegate
import org.tianea.secretary.core.agent.graph.nodes.reactEndNodeDelegate
import org.tianea.secretary.core.agent.graph.nodes.reactStartNodeDelegate
import org.tianea.secretary.core.agent.graph.nodes.reflectNodeDelegate
import org.tianea.secretary.core.agent.graph.nodes.retrieveKnowHowNodeDelegate
import org.tianea.secretary.core.agent.graph.nodes.touchKnowHowNodeDelegate
import org.tianea.secretary.core.agent.knowhow.KnowHowConsolidator
import org.tianea.secretary.core.agent.knowhow.KnowHowProperties
import org.tianea.secretary.core.agent.knowhow.KnowHowReflector
import org.tianea.secretary.core.agent.knowhow.KnowHowStore
import org.tianea.secretary.core.scheduling.ChatContext
import org.tianea.secretary.telegram.TelegramReactionSender

/**
 * 비서의 chat-only Koog `strategy { }` 그래프를 스프링 빈으로 제공하는 설정.
 *
 * `AIAgentGraphStrategy`는 실행 상태가 없는 그래프 청사진이라 모든 agent 인스턴스가 안전하게 공유할 수 있다 → 싱글턴 빈으로 한 번만 빌드한다. 호출별
 * 데이터(chatId·messageId)는 그래프 빌드가 아니라 노드 실행 시점에 [ChatContext] 코루틴 컨텍스트로 흘러든다.
 *
 * 이 파일은 **노드 위임 + 엣지 선언**만 담당한다. 각 노드의 타입·이름·실행 로직은 `nodes/` 패키지의 `*Node.kt` 파일 하나씩에
 * `<Name>NodeDelegate(...)` factory로 분리되어 있다 — 한 노드를 손볼 때는 그 파일만 열면 된다.
 */
@Configuration
class ChatStrategyConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 그래프 모양: `nodeStart → reactStart → preprocess → retrieveKnowHow → callLLM → touchKnowHow →
     * emitText → reflect → consolidate → reactEnd → nodeFinish`
     *
     * 각 노드의 정의·역할은 해당 `nodes/<Name>Node.kt`의 KDoc 참고.
     *
     * `touchKnowHow`는 `callLLM` 성공 직후 채택된 노하우의 사용 카운트를 갱신한다 — retrieveKnowHow 단계에서 미리 touch하면 LLM이
     * 실패해 사용자에게 전달되지 못한 노하우도 사용 기록이 남아 recency가 왜곡된다.
     *
     * 표식 제거의 **실패 경로**는 이 그래프가 아니라 `AssistantAgentFactory`의 `EventHandler`
     * (`onAgentExecutionFailed`)가 담당한다. `callLLM`이 예외로 실패하면 그래프가 거기서 중단되어 `reactEnd`에 도달하지 못하므로, 정상
     * 종료는 `reactEnd` 노드가, 예외 종료는 `EventHandler`가 나눠 맡아 두 경로 모두에서 표식 제거를 보장한다.
     *
     * 시스템 프롬프트는 이 그래프가 아니라 `AssistantAgentFactory`의 `AIAgentConfig.withSystemPrompt`로 한 번만 주입한다.
     * `setupPrompt` 노드에서 매 턴 `appendPrompt { system(...) }`를 호출하면 `ChatMemory`가 시스템 메시지를 매 턴 누적해
     * 프롬프트가 부풀어 오른다.
     */
    @Bean
    fun chatStrategy(
        reactionSender: TelegramReactionSender,
        knowHowStore: KnowHowStore,
        knowHowReflector: KnowHowReflector,
        knowHowConsolidator: KnowHowConsolidator,
        knowHowProperties: KnowHowProperties,
    ): AIAgentGraphStrategy<String, String> {
        val enabled = knowHowProperties.enabled
        val topK = knowHowProperties.retrieval.topK
        val tokenBudget = knowHowProperties.retrieval.tokenBudget
        return strategy("secretary-chat") {
            val reactStart by reactStartNodeDelegate(reactionSender)
            val preprocess by preprocessNodeDelegate()
            val retrieveKnowHow by
                retrieveKnowHowNodeDelegate(knowHowStore, enabled, topK, tokenBudget)
            val callLLM by callLlmNodeDelegate(log)
            val touchKnowHow by touchKnowHowNodeDelegate(knowHowStore)
            val emitText by emitTextNodeDelegate()
            val reflect by reflectNodeDelegate(knowHowReflector, enabled)
            val consolidate by consolidateNodeDelegate(knowHowConsolidator, enabled)
            val reactEnd by reactEndNodeDelegate(reactionSender)

            edge(nodeStart forwardTo reactStart)
            edge(reactStart forwardTo preprocess)
            edge(preprocess forwardTo retrieveKnowHow)
            edge(retrieveKnowHow forwardTo callLLM)
            edge(callLLM forwardTo touchKnowHow)
            edge(touchKnowHow forwardTo emitText)
            edge(emitText forwardTo reflect)
            edge(reflect forwardTo consolidate)
            edge(consolidate forwardTo reactEnd)
            edge(reactEnd forwardTo nodeFinish)
        }
    }
}
