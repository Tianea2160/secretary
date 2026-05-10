package org.tianea.secretary.core.session

import org.springframework.stereotype.Service

/**
 * 세션 관련 비즈니스 로직 단일 출처. ChatCommands(@Command), SlashCommandCatalog,
 * 향후 다른 dispatch surface가 모두 여기에 위임한다.
 */
@Service
class SessionService(
    private val state: SessionState,
    private val repo: SessionRepository,
) {
    fun newSession(): String = state.newSession()

    fun currentSession(): String? = state.current

    fun useSession(id: String) {
        state.set(id)
    }

    fun listSessions(): List<String> = repo.listConversationIds()
}
