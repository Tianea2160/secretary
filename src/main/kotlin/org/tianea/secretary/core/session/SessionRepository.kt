package org.tianea.secretary.core.session

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.stereotype.Repository

@Repository
class SessionRepository(
    private val jdbc: JdbcTemplate,
) {
    fun listConversationIds(): List<String> =
        jdbc.queryForList<String>(
            "SELECT DISTINCT conversation_id FROM SPRING_AI_CHAT_MEMORY ORDER BY conversation_id",
        )
}