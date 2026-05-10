package org.tianea.secretary.core.session

import io.hypersistence.tsid.TSID
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

@Component
class SessionState {
    private val ref = AtomicReference<String?>(null)

    val current: String? get() = ref.get()

    fun set(sessionId: String) {
        ref.set(sessionId)
    }

    fun newSession(): String {
        val tsid = TSID.fast().toString()
        ref.set(tsid)
        return tsid
    }

    fun currentOrNew(): String = ref.get() ?: newSession()
}