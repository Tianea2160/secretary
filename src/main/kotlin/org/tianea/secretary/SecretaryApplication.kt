package org.tianea.secretary

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SecretaryApplication

fun main(args: Array<String>) {
    runApplication<SecretaryApplication>(*args)

    runBlocking {
        val apiKey = System.getenv("GOOGLE_API_KEY") ?: "AIzaSyByI-B_NcydsyjSLGkL0WX99-CXTziP9q8"
        val agent =
            AIAgent(
                promptExecutor = simpleGoogleAIExecutor(apiKey), // or Anthropic, Google, OpenRouter, etc.
                systemPrompt = "You are a helpful assistant. Answer user questions concisely.",
                llmModel = GoogleModels.Gemini2_5Flash,
            )

        val result = agent.run("Hello! How can you help me?")
        println(result)
    }
}
