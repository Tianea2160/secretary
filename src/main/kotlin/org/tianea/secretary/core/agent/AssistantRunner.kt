package org.tianea.secretary.core.agent

import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component
import org.tianea.secretary.core.registry.SessionAgentRegistry
import org.tianea.secretary.core.scheduling.ChatContext

@Component
class AssistantRunner(
    private val factory: AssistantAgentFactory,
    private val registry: SessionAgentRegistry,
) {
    fun run(
        chatId: Long,
        sessionId: String,
        prompt: String,
        messageId: Int?,
    ): String =
        runBlocking(ChatContext(chatId, sessionId, messageId)) {
            var agent = registry.getAgent(sessionId)

            if (agent == null) {
                agent = factory.create()
                registry.register(sessionId, agent)
            }

            agent.run(prompt, sessionId)
        }
}
