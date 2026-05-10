package org.tianea.secretary.config

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.model.PromptExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfig {
    @Bean
    fun assistantAgent(
        promptExecutor: PromptExecutor,
        historyProvider: ChatHistoryProvider,
        @Value($$"${secretary.chat.memory.window-size}") windowSize: Int,
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
        }
}
