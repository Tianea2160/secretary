package org.tianea.secretary.core.agent.knowhow

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * `know_how` 테이블 Spring Data JPA 저장소.
 *
 * CRUD는 [JpaRepository] 기본 메서드로 처리하고, pgvector 코사인 거리 검색과
 * 사용 카운트 갱신만 커스텀 쿼리로 정의한다. 코사인 거리 함수 `cosine_distance`는
 * `hibernate-vector` 모듈이 PostgreSQL 다이얼렉트에 등록하는 HQL 함수다.
 */
interface KnowHowRepository : JpaRepository<KnowHowEntity, String> {
    /**
     * [chatId] 사용자의 노하우를 쿼리 임베딩과의 코사인 거리 오름차순으로 검색한다.
     *
     * 거리가 작을수록 유사하므로 `ORDER BY cosine_distance` 오름차순 + [Pageable]로 상위 N개를 자른다.
     *
     * @param chatId 사용자 격리 키.
     * @param queryEmbedding 쿼리 임베딩 벡터.
     * @param pageable 상위 N개 제한.
     * @return `[KnowHowEntity, 코사인 거리(Double)]` 쌍의 리스트.
     */
    @Query(
        """
        SELECT e, cosine_distance(e.embedding, :queryEmbedding)
          FROM KnowHowEntity e
         WHERE e.chatId = :chatId
         ORDER BY cosine_distance(e.embedding, :queryEmbedding)
        """,
    )
    fun searchByEmbedding(
        chatId: Long,
        queryEmbedding: FloatArray,
        pageable: Pageable,
    ): List<Array<Any>>

    /**
     * 노하우의 `use_count`를 1 증가시키고 `last_used_at`을 [usedAt]으로 갱신한다.
     *
     * @param id 사용된 노하우 id.
     * @param usedAt 사용 시각.
     * @return 갱신된 행 수 (없으면 0).
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE KnowHowEntity e
           SET e.useCount = e.useCount + 1,
               e.lastUsedAt = :usedAt
         WHERE e.id = :id
        """,
    )
    fun incrementUsage(
        id: String,
        usedAt: Instant,
    ): Int
}
