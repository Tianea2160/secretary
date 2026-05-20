package org.tianea.secretary.core.registry

import ai.koog.agents.core.agent.AIAgent
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

@Component
class SessionAgentRegistry {
    val map: ConcurrentMap<String, AIAgent<String, String>> = ConcurrentHashMap()

    fun getAgent(sessionId: String): AIAgent<String, String>? = map[sessionId]

    fun register(
        sessionId: String,
        agent: AIAgent<String, String>,
    ) {
        map[sessionId] = agent
    }
}
