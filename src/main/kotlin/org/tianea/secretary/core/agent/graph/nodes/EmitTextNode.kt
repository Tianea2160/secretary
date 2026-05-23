package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.prompt.message.Message

/**
 * `emitText` 노드 정의.
 *
 * [Message.Response]에서 텍스트만 추출해 그래프 출력 타입을 `String`으로 좁힌다.
 * `AIAgent<String, String>`의 전체 출력 타입과 맞추기 위한 어댑터 노드.
 */
fun emitTextNodeDelegate(): AIAgentNodeDelegate<Message.Response, String> = node("emitText") { response -> response.content }
