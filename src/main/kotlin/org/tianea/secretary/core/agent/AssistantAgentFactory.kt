package org.tianea.secretary.core.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.annotation.ExperimentalAgentsApi
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.SimilaritySearchStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.spring.ai.vectorstore.KoogVectorStore
import org.tianea.secretary.core.scheduling.SchedulingTools

/**
 * 호출마다 새 [AIAgent] 인스턴스를 생성하는 factory.
 *
 * Koog `OpenTelemetry` feature의 `SpanCollector`가 agent 인스턴스 단위로 span tree를 관리하기
 * 때문에 단일 인스턴스를 여러 thread에서 공유하면 race가 발생한다. 인스턴스를 호출마다 새로
 * 만들면 span tree가 격리되어 텔레그램 워커와 Quartz 스케줄러가 독립적으로 동시 실행 가능하다.
 *
 * `ChatHistoryProvider`/`KoogVectorStore`/`SchedulingTools`는 공유 의존성이라 새 agent도
 * 동일한 채팅 메모리·벡터 스토어·도구 레지스트리를 그대로 사용한다.
 */
class AssistantAgentFactory(
    private val promptExecutor: PromptExecutor,
    private val historyProvider: ChatHistoryProvider,
    private val vectorStorage: KoogVectorStore,
    private val schedulingTools: SchedulingTools,
    private val llmModel: LLModel,
    private val windowSize: Int,
    private val topK: Int,
    private val tracingVerbose: Boolean,
) {
    @OptIn(ExperimentalAgentsApi::class)
    fun create(): AIAgent<String, String> =
        AIAgent(
            promptExecutor = promptExecutor,
            llmModel = llmModel,
            systemPrompt = "You are a helpful Korean-speaking assistant. Answer concisely.",
            toolRegistry = ToolRegistry { tools(schedulingTools) },
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
        }
}
