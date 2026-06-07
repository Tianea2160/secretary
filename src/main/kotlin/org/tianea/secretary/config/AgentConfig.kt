package org.tianea.secretary.config

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.spring.ai.vectorstore.KoogVectorStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.tianea.secretary.core.agent.AssistantAgentFactory
import org.tianea.secretary.core.agent.knowhow.KnowHowConsolidator
import org.tianea.secretary.core.agent.knowhow.KnowHowProperties
import org.tianea.secretary.core.agent.knowhow.KnowHowReflector
import org.tianea.secretary.core.agent.knowhow.KnowHowStore
import org.tianea.secretary.telegram.TelegramReactionSender

@Configuration
class AgentConfig {
    @Bean
    fun llmModel(
        @Value($$"${spring.ai.model.chat}") chatProvider: String,
        @Value($$"${spring.ai.ollama.chat.options.model:}") ollamaChatModel: String,
    ): LLModel = resolveLlmModel(chatProvider, ollamaChatModel)

    @Bean
    fun knowHowReflector(
        promptExecutor: PromptExecutor,
        llmModel: LLModel,
        knowHowProperties: KnowHowProperties,
        objectMapper: ObjectMapper,
    ): KnowHowReflector =
        KnowHowReflector(
            promptExecutor = promptExecutor,
            model = llmModel,
            minImportance = knowHowProperties.reflection.minImportance,
            objectMapper = objectMapper,
        )

    @Bean
    fun knowHowConsolidator(
        promptExecutor: PromptExecutor,
        llmModel: LLModel,
        store: KnowHowStore,
        objectMapper: ObjectMapper,
    ): KnowHowConsolidator =
        KnowHowConsolidator(
            promptExecutor = promptExecutor,
            model = llmModel,
            store = store,
            objectMapper = objectMapper,
        )

    @Bean
    fun assistantAgentFactory(
        promptExecutor: PromptExecutor,
        chatStrategy: AIAgentGraphStrategy<String, String>,
        historyProvider: ChatHistoryProvider,
        vectorStorage: KoogVectorStore,
        reactionSender: TelegramReactionSender,
        llmModel: LLModel,
        @Value("\${secretary.chat.memory.window-size}") windowSize: Int,
        @Value("\${secretary.chat.long-term-memory.top-k}") topK: Int,
        @Value("\${secretary.tracing.verbose}") tracingVerbose: Boolean,
    ): AssistantAgentFactory =
        AssistantAgentFactory(
            promptExecutor = promptExecutor,
            chatStrategy = chatStrategy,
            historyProvider = historyProvider,
            vectorStorage = vectorStorage,
            reactionSender = reactionSender,
            llmModel = llmModel,
            windowSize = windowSize,
            topK = topK,
            tracingVerbose = tracingVerbose,
        )

    /**
     * `spring.ai.model.chat` 값으로 Koog `LLModel`을 결정한다.
     *
     * Koog Spring AI starter는 `ChatModel` 빈의 클래스명으로 provider를 자동 감지하므로, `PromptExecutor`는 이 메서드와
     * 무관하게 yaml 한 줄만으로 라우팅이 전환된다. 이 메서드는 Koog 측에 capability / contextLength 메타데이터를 알려주는 역할만 한다.
     */
    private fun resolveLlmModel(chatProvider: String, ollamaChatModel: String): LLModel =
        when (chatProvider) {
            "ollama" -> {
                require(ollamaChatModel.isNotBlank()) {
                    "spring.ai.ollama.chat.options.model must be set when spring.ai.model.chat=ollama"
                }
                LLModel(
                    provider = LLMProvider.Ollama,
                    id = ollamaChatModel,
                    capabilities =
                        listOf(
                            LLMCapability.Temperature,
                            LLMCapability.Schema.JSON.Basic,
                            LLMCapability.Tools,
                            LLMCapability.Vision.Image,
                        ),
                    contextLength = 262_144,
                )
            }

            else -> {
                error("Unsupported spring.ai.model.chat=$chatProvider (expected: ollama)")
            }
        }
}
