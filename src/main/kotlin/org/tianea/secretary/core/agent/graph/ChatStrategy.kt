package org.tianea.secretary.core.agent.graph

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.prompt.message.Message

/**
 * Koog `strategy { }` graph DSL로 작성한 비서의 chat-only 전략.
 *
 * 그래프 모양 (선형):
 * `nodeStart → preprocess → callLLM → emitText → nodeFinish`
 *
 * 각 노드의 역할:
 * - `preprocess` : 사용자 입력을 trim. 향후 의도 분류·정규화 노드를 끼워 넣을 자리.
 * - `callLLM`    : 내장 [nodeLLMRequest] — 입력 문자열을 user 메시지로 누적해 LLM 호출, 응답 반환.
 * - `emitText`   : [Message.Response]에서 텍스트만 추출해 `AIAgent<String, String>` 출력 타입 유지.
 *
 * 시스템 프롬프트는 이 strategy 내부가 아니라 `AssistantAgentFactory`의 `AIAgentConfig.withSystemPrompt`로
 * 한 번만 주입한다. `setupPrompt` 노드에서 매 턴 `appendPrompt { system(...) }`를 호출하면
 * `ChatMemory`가 시스템 메시지를 매 턴 누적해 프롬프트가 부풀어 오른다.
 *
 * 다음 단계에서 도구·분기·서브그래프를 추가할 때는 이 파일에 노드를 더 선언하고 엣지를 잇기만 하면 된다 —
 * 자세한 사용법은 `docs/koog-strategy-graph.md` 참고.
 */
internal fun chatStrategy(): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("secretary-chat") {
        val preprocess by node<String, String>("preprocess") { input -> input.trim() }
        val callLLM by nodeLLMRequest("callLLM")
        val emitText by node<Message.Response, String>("emitText") { response -> response.content }

        edge(nodeStart forwardTo preprocess)
        edge(preprocess forwardTo callLLM)
        edge(callLLM forwardTo emitText)
        edge(emitText forwardTo nodeFinish)
    }
