package org.tianea.secretary.core.agent.knowhow

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 노하우 메모리 설정 (`know-how.*`). `@ConfigurationPropertiesScan`으로 바인딩된다.
 *
 * @property enabled false면 retrieve·reflect·consolidate 세 노드가 모두 pass-through.
 * @property retrieval 검색·주입 단계 설정.
 * @property reflection 노하우 후보 추출 단계 설정.
 * @property rerank 재랭킹 가중합 설정.
 */
@ConfigurationProperties(prefix = "know-how")
data class KnowHowProperties(
    val enabled: Boolean = true,
    val retrieval: Retrieval = Retrieval(),
    val reflection: Reflection = Reflection(),
    val rerank: Rerank = Rerank(),
) {
    /**
     * @property topK 유사도 검색 상위 K.
     * @property tokenBudget 주입 노하우 합산 근사 토큰 상한.
     */
    data class Retrieval(val topK: Int = 5, val tokenBudget: Int = 1200)

    /** @property minImportance 이 미만 후보는 저장하지 않음. */
    data class Reflection(val minImportance: Double = 0.5)

    /**
     * 재랭킹 가중합 `score = wSim·similarity + wRec·recency + wImp·importance + wFreq·frequency`. 네 가중치
     * 합은 1.0을 권장하며 모두 튜닝 가능하다.
     *
     * @property weightSimilarity 질의 적합도(검색) 가중치. AI 임베딩 산출이지만 결정적.
     * @property weightRecency 최근 사용(시간 감쇠) 가중치 — 결정적(사용 통계).
     * @property weightImportance LLM 재사용 가치 가중치 — 비결정적(모델 판단).
     * @property weightFrequency 사용 빈도(use_count) 가중치 — 결정적(사용 통계).
     * @property frequencySaturationK frequency 포화 상수. `useCount/(useCount+K)`가 useCount=K에서 0.5가
     *   된다.
     * @property halfLifeHours recency 지수 감쇠 반감기(시간).
     */
    data class Rerank(
        val weightSimilarity: Double = 0.25,
        val weightRecency: Double = 0.25,
        val weightImportance: Double = 0.25,
        val weightFrequency: Double = 0.25,
        val frequencySaturationK: Double = 5.0,
        val halfLifeHours: Double = 72.0,
    )
}
