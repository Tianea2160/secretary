package org.tianea.secretary.core.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.annotation.ExperimentalAgentsApi
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.SimilaritySearchStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.spring.ai.vectorstore.KoogVectorStore
import org.tianea.secretary.core.agent.graph.chatStrategy
import org.tianea.secretary.core.scheduling.SchedulingTools

private const val SYSTEM_PROMPT = "You are a helpful English-speaking assistant. Answer concisely."

/**
 * `AIAgentConfig`의 `maxAgentIterations` 기본값은 3. 현재 선형 그래프(`nodeStart → preprocess →
 * callLLM → emitText → nodeFinish`)는 노드 5개라서 기본값으로는 즉시 한도 초과 예외가 나며,
 * 이후 단계에서 분기·도구 서브그래프·사이클을 더하면 여유가 더 필요하다. 50으로 잡아 두면
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
 * `ChatHistoryProvider`/`KoogVectorStore`는 공유 의존성이라 새 agent도
 * 동일한 채팅 메모리·벡터 스토어를 그대로 사용한다.
 *
 * 에이전트는 [chatStrategy]가 정의한 Koog `strategy { }` 그래프로 동작한다 — 이번 단계에서는
 * `nodeStart → preprocess → callLLM → emitText → nodeFinish` 선형 그래프. 분기·도구 노드는
 * `chatStrategy` 파일에 노드를 추가해 확장한다. 자세한 DSL 설명은 `docs/koog-strategy-graph.md`.
 *
 * `schedulingTools`는 다음 단계에서 도구 서브그래프를 추가할 때 다시 바인딩한다 — 현재는
 * `ToolRegistry { }`(빈 레지스트리)로 두고 의존성만 유지한다.
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
            agentConfig =
                AIAgentConfig.withSystemPrompt(
                    prompt = SYSTEM_PROMPT,
                    llm = llmModel,
                    maxAgentIterations = MAX_AGENT_ITERATIONS,
                ),
            strategy = chatStrategy(),
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
        }
}
