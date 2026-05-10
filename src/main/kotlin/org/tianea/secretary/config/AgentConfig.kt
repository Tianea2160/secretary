package org.tianea.secretary.config

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.annotation.ExperimentalAgentsApi
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.retrieval.SimilaritySearchStrategy
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.spring.ai.vectorstore.KoogVectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfig {
    @OptIn(ExperimentalAgentsApi::class)
    @Bean
    fun assistantAgent(
        promptExecutor: PromptExecutor,
        historyProvider: ChatHistoryProvider,
        vectorStorage: KoogVectorStore,
        @Value($$"${secretary.chat.memory.window-size}") windowSize: Int,
        @Value($$"${secretary.chat.long-term-memory.top-k}") topK: Int,
    ): AIAgent<String, String> =
        AIAgent(
            promptExecutor = promptExecutor,
            llmModel = GoogleModels.Gemini2_5Flash,
            systemPrompt = "You are a helpful Korean-speaking assistant. Answer concisely.",
        ) {
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
