package org.tianea.secretary.core.registry

import ai.koog.agents.core.agent.AIAgent
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * 세션별 [AIAgent] 인스턴스를 보관하는 레지스트리.
 *
 * 동일 sessionId에 대한 동시 첫 호출이 별도 인스턴스를 만들지 않도록 [ConcurrentMap.computeIfAbsent]로
 * 단일 인스턴스를 atomic하게 보장한다. Koog `OpenTelemetry` feature의 `SpanCollector`가 agent 인스턴스
 * 단위로 span tree를 관리하므로 같은 세션의 동시 호출이 같은 인스턴스를 공유해야 안전하다.
 */
@Component
class SessionAgentRegistry {
    private val map: ConcurrentMap<String, AIAgent<String, String>> = ConcurrentHashMap()

    /**
     * 세션 ID에 해당하는 agent가 있으면 반환하고, 없으면 [factory]로 만들어 등록한 뒤 반환한다.
     * 동시성: 동일 sessionId의 동시 호출이 들어와도 [factory]는 최대 한 번만 실행된다.
     */
    fun getOrCreate(
        sessionId: String,
        factory: () -> AIAgent<String, String>,
    ): AIAgent<String, String> = map.computeIfAbsent(sessionId) { factory() }
}
