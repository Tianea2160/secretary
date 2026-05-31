package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import org.slf4j.Logger

/**
 * `callLLM` → `touchKnowHow` 간 데이터 캐리어.
 *
 * @property response LLM이 반환한 응답 메시지.
 * @property adoptedIds 이번 호출에서 프롬프트에 주입된 노하우의 ID 목록. 호출이 성공한 경우에만 [touchKnowHowNodeDelegate]가 사용
 *   카운트를 갱신한다.
 */
data class LlmCallResult(val response: Message.Assistant, val adoptedIds: List<String>)

/**
 * `callLLM` 노드 정의.
 *
 * 노하우 블록이 있으면 `"$노하우블록\n\n$원본입력"` 형태의 user 메시지를 추가해 LLM을 호출한 뒤, `rewritePrompt`로 우리가 추가한 user 메시지를
 * **원본 입력만**으로 되돌린다. `SpringAiChatHistoryProvider`가 매 턴 user 메시지를 영속하므로, 이 되돌리기를 빠뜨리면 노하우 블록이
 * ChatMemory에 누적된다.
 *
 * 되돌릴 대상은 `appendPrompt` 직전 prompt 길이를 캡쳐해 그 인덱스로 정확히 식별한다 — `indexOfLast { it is Message.User }`는
 * [LongTermMemory] 같은 feature가 retrieval 결과를 user-role 메시지로 주입하는 경우 잘못된 메시지를 가리킬 수 있어 사용하지 않는다.
 *
 * `llm.writeSession` 등은 `node { }` 람다 receiver(`AIAgentGraphContextBase`)에서 제공되므로 추가 의존성 없이 factory
 * 함수로 노출한다.
 */
fun callLlmNodeDelegate(log: Logger): AIAgentNodeDelegate<KnowHowWithInput, LlmCallResult> =
    node("callLLM") { carrier ->
        llm.writeSession {
            val knowHowBlock = carrier.knowHowBlock
            if (knowHowBlock == null) {
                appendPrompt { user(carrier.originalInput) }
                return@writeSession LlmCallResult(requestLLM(), carrier.adoptedIds)
            }

            val injectedIdx = prompt.messages.size
            appendPrompt { user("$knowHowBlock\n\n${carrier.originalInput}") }

            val response = requestLLM()
            log.trace("CallLLM response: {}", response)

            rewritePrompt { currentPrompt ->
                val messages = currentPrompt.messages.toMutableList()
                val target = messages.getOrNull(injectedIdx)
                if (target is Message.User) {
                    messages[injectedIdx] =
                        target.copy(parts = listOf(MessagePart.Text(carrier.originalInput)))
                }
                currentPrompt.withMessages { messages }
            }

            LlmCallResult(response, carrier.adoptedIds)
        }
    }
