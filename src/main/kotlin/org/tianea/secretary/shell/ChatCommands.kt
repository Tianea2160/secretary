package org.tianea.secretary.shell

import ai.koog.agents.core.agent.AIAgent
import kotlinx.coroutines.runBlocking
import org.springframework.shell.core.command.annotation.Command
import org.springframework.shell.core.command.annotation.Option
import org.springframework.stereotype.Component

@Component
class ChatCommands(
    private val agent: AIAgent<String, String>,
) {
    @Command(name = ["ask"], description = "Ask the AI assistant a question")
    fun ask(
        @Option(longName = "q", shortName = 'q', required = true, description = "Question to ask")
        question: String,
    ): String = runBlocking { agent.run(question) }
}
