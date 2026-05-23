package org.tianea.secretary.core.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.annotation.ExperimentalAgentsApi
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.SimilaritySearchStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.spring.ai.vectorstore.KoogVectorStore
import kotlinx.coroutines.currentCoroutineContext
import org.tianea.secretary.core.scheduling.ChatContext
import org.tianea.secretary.telegram.TelegramReactionSender

private const val SYSTEM_PROMPT = "You are a helpful English-speaking assistant. Answer concisely."

/**
 * `AIAgentConfig`의 `maxAgentIterations` 기본값은 3. 현재 선형 그래프(`nodeStart → reactStart →
 * preprocess → callLLM → emitText → nodeFinish`)는 노드 6개라서 기본값으로는 즉시 한도 초과 예외가
 * 나며, 이후 단계에서 분기·도구 서브그래프·사이클을 더하면 여유가 더 필요하다. 50으로 잡아 두면
 * 본 단계와 가까운 확장에서 모두 안전.
 */
private const val MAX_AGENT_ITERATIONS = 50

/**
 * 호출마다 새 [AIAgent] 인스턴스를 생성하는 factory.
 *
 * Koog `OpenTelemetry` feature의 `SpanCollector`가 agent 인스턴스 단위로 span tree를 관리하기
 * 때문에 단일 인스턴스를 여러 thread에서 공유하면 race가 발생한다. 인스턴스를 호출마다 새로
 * 만들면 span tree가 격리되어 텔레그램 워커와 Quartz 스케줄러가 독립적으로 동시 실행 가능하다.
 *
 * `chatStrategy`/`ChatHistoryProvider`/`KoogVectorStore`는 공유 의존성이라 새 agent도 동일한
 * 그래프 청사진·채팅 메모리·벡터 스토어를 그대로 사용한다. 호출별 데이터(chatId·messageId)는
 * agent 생성이 아니라 [ChatContext] 코루틴 컨텍스트로 그래프 노드·핸들러에 흘러든다.
 *
 * 처리중 표식(텔레그램 메시지 리액션)은 `chatStrategy`의 `reactStart` 노드가 부착하고, 정상 완료 시
 * `reactEnd` 노드가 제거한다. `callLLM`이 예외로 실패하면 그래프가 중단되어 `reactEnd`에 도달하지
 * 못하므로, 예외 경로의 제거만 `EventHandler`의 `onAgentExecutionFailed`가 보완한다.
 */
class AssistantAgentFactory(
    private val promptExecutor: PromptExecutor,
    private val chatStrategy: AIAgentGraphStrategy<String, String>,
    private val historyProvider: ChatHistoryProvider,
    private val vectorStorage: KoogVectorStore,
    private val reactionSender: TelegramReactionSender,
    private val llmModel: LLModel,
    private val windowSize: Int,
    private val topK: Int,
    private val tracingVerbose: Boolean,
) {
    @OptIn(ExperimentalAgentsApi::class)
    fun create(): AIAgent<String, String> =
        AIAgent(
            promptExecutor = promptExecutor,
            agentConfig =
                AIAgentConfig.withSystemPrompt(
                    prompt = SYSTEM_PROMPT,
                    llm = llmModel,
                    maxAgentIterations = MAX_AGENT_ITERATIONS,
                ),
            strategy = chatStrategy,
            toolRegistry = ToolRegistry { },
        ) {
            install(OpenTelemetry) {
                setVerbose(tracingVerbose)
                addLangfuseExporter()
            }
            install(ChatMemory) {
                chatHistoryProvider = historyProvider
                windowSize(windowSize)
            }
            install(LongTermMemory) {
                retrieval {
                    storage = vectorStorage
                    searchStrategy = SimilaritySearchStrategy(topK = topK)
                }
            }
            install(EventHandler) {
                onAgentExecutionFailed { clearProcessingReaction() }
            }
        }

    /**
     * 처리중 표식을 제거한다. 정상 완료는 `chatStrategy`의 `reactEnd` 노드가 맡고, 이 함수는
     * `callLLM` 예외로 그래프가 `reactEnd`에 도달하지 못하는 실패 경로만 `EventHandler`의
     * `onAgentExecutionFailed`를 통해 보완한다. [ChatContext]가 없거나 messageId가 `null`이면 no-op.
     */
    private suspend fun clearProcessingReaction() {
        val ctx = currentCoroutineContext()[ChatContext] ?: return
        ctx.messageId?.let { reactionSender.clearProcessing(ctx.chatId, it) }
    }
}
