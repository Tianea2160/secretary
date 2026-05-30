package org.tianea.secretary.core.agent.knowhow

import java.time.Instant

/**
 * 절차적 노하우 도메인 모델.
 *
 * 사용자와의 대화에서 추출된 "재사용 가능한 방법론"을 표현한다. raw 메시지 `vector_store`와 별개의 `know_how` 테이블에 저장되며,
 * intent/useCount/importance/lastUsedAt 등 자체 수명주기를 가진다.
 *
 * @property id TSID 문자열. 저장소 삽입 전 호출자가 발급한다.
 * @property chatId 사용자 격리 키. 검색 시 반드시 필터 조건으로 사용한다.
 * @property intent "언제 쓰는가" 한 줄 요약. 임베딩 색인의 기준 텍스트.
 * @property body 실제 절차·방법론 본문.
 * @property importance LLM이 매긴 재사용 가치 (0.0 ~ 1.0).
 * @property useCount [KnowHowStore.touch]가 호출될 때마다 증가한다.
 * @property createdAt 최초 저장 시각.
 * @property updatedAt 지식 본문(intent/body/importance/embedding)이 마지막으로 수정된 시각. 사용 카운트만 증가하는
 *   [KnowHowStore.touch] 호출은 [lastUsedAt]이 추적하며 이 값은 변하지 않는다.
 * @property lastUsedAt [KnowHowStore.touch] 마지막 호출 시각. 재랭킹 recency decay 기준.
 * @property sourceSessionId 이 노하우를 추출한 대화 세션 ID.
 */
data class KnowHow(
    val id: String,
    val chatId: Long,
    val intent: String,
    val body: String,
    val importance: Double,
    val useCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastUsedAt: Instant?,
    val sourceSessionId: String,
)

/**
 * 유사도 검색 결과. [KnowHow]에 코사인 유사도 점수와 재랭킹 점수를 함께 담는다.
 *
 * @property knowHow 원본 도메인 객체.
 * @property similarity pgvector 코사인 거리를 [0, 1] 유사도로 변환한 값.
 * @property rerankScore recency decay × importance × similarity 를 곱한 최종 재랭킹 점수.
 *   [KnowHowStore.rerank] 호출 전에는 similarity 와 동일.
 */
data class ScoredKnowHow(
    val knowHow: KnowHow,
    val similarity: Double,
    val rerankScore: Double = similarity,
)
