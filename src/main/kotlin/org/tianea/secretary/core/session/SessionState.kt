package org.tianea.secretary.core.session

import io.hypersistence.tsid.TSID
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * in-memory chat_id → conversation_id 매핑. 재시작 시 매핑 소실 → [currentOrNew]가 새 TSID 발급.
 */
@Component
class SessionState {
    private val map = ConcurrentHashMap<Long, String>()

    fun current(chatId: Long): String? = map[chatId]

    fun newSession(chatId: Long): String {
        val tsid = TSID.fast().toString()
        map[chatId] = tsid
        return tsid
    }

    fun currentOrNew(chatId: Long): String = map[chatId] ?: newSession(chatId)
}
