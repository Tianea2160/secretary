package org.tianea.secretary.shell.command

import ai.koog.agents.core.agent.AIAgent
import kotlinx.coroutines.runBlocking
import org.springframework.shell.core.command.annotation.Arguments
import org.springframework.shell.core.command.annotation.Command
import org.springframework.shell.core.command.annotation.Option
import org.springframework.stereotype.Component
import org.tianea.secretary.core.session.SessionService
import org.tianea.secretary.core.session.SessionState

@Component
class ChatCommands(
    private val agent: AIAgent<String, String>,
    private val sessionState: SessionState,
    private val sessions: SessionService,
) {
    @Command(name = ["ask"], description = "Ask the AI assistant a question")
    fun ask(
        @Option(longName = "session", shortName = 's', description = "Session TSID (defaults to current)")
        session: String?,
        @Arguments
        prompt: Array<String>?,
    ): String {
        if (prompt.isNullOrEmpty()) return "Usage: ask <your question> [--session <tsid>]"
        val question = prompt.joinToString(" ")
        val sessionId = session?.takeIf { it.isNotBlank() } ?: sessionState.currentOrNew()
        sessionState.set(sessionId)
        val answer = runBlocking { agent.run(question, sessionId) }
        return "[session=$sessionId]\n$answer"
    }

    @Command(name = ["session", "new"], description = "Start a new chat session (generates a fresh TSID)")
    fun sessionNew(): String = "New session: ${sessions.newSession()}"

    @Command(name = ["session", "current"], description = "Show the current session ID")
    fun sessionCurrent(): String = sessions.currentSession() ?: "(no active session)"

    @Command(name = ["session", "use"], description = "Switch to an existing session ID")
    fun sessionUse(
        @Option(longName = "id", required = true, description = "Session TSID to switch to")
        id: String,
    ): String {
        sessions.useSession(id)
        return "Switched to session: $id"
    }
}
