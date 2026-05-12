package org.tianea.secretary.core.session

import org.springframework.stereotype.Service

@Service
class SessionService(
    private val state: SessionState,
    private val repo: SessionRepository,
) {
    fun currentOrNew(chatId: Long): String = state.currentOrNew(chatId)

    fun listSessions(): List<String> = repo.listConversationIds()
}
