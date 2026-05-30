package org.tianea.secretary.core.agent.knowhow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.Array
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * `know_how` 테이블에 매핑되는 JPA 엔티티.
 *
 * 도메인 모델 [KnowHow]와 분리한다 — [KnowHow]는 임베딩을 들고 다니지 않는 순수 도메인 객체이고, 이 엔티티는 pgvector `vector(4096)`
 * 컬럼([embedding])까지 포함하는 영속화 표현이다. 변환은 [KnowHowStore]가 담당한다.
 *
 * [embedding]은 Hibernate `hibernate-vector` 모듈의 [SqlTypes.VECTOR] 매핑으로 pgvector `vector(4096)` 컬럼과
 * 연결된다. 4096차원은 HNSW/IVFFlat 인덱스 한계(≤2000)를 넘으므로 인덱스 없이 풀스캔 검색한다.
 *
 * 테이블 자체는 [KnowHowStore.initSchema]가 `CREATE TABLE IF NOT EXISTS`로 생성하며 Hibernate `ddl-auto`에 의존하지
 * 않는다 — `vector` extension을 테이블 생성 전에 보장해야 하기 때문.
 */
@Entity
@Table(name = "know_how")
class KnowHowEntity(
    @Id @Column(name = "id") var id: String,
    @Column(name = "chat_id", nullable = false) var chatId: Long,
    @Column(name = "intent", nullable = false, columnDefinition = "text") var intent: String,
    @Column(name = "body", nullable = false, columnDefinition = "text") var body: String,
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSIONS)
    @Column(name = "embedding", nullable = false)
    var embedding: FloatArray,
    @Column(name = "importance", nullable = false) var importance: Double,
    @Column(name = "use_count", nullable = false) var useCount: Int,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "last_used_at") var lastUsedAt: Instant?,
    @Column(name = "source_session_id", nullable = false) var sourceSessionId: String,
) {
    /** 영속화 엔티티를 순수 도메인 모델로 변환한다. 임베딩은 도메인 모델에 노출하지 않는다. */
    fun toDomain(): KnowHow =
        KnowHow(
            id = id,
            chatId = chatId,
            intent = intent,
            body = body,
            importance = importance,
            useCount = useCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastUsedAt = lastUsedAt,
            sourceSessionId = sourceSessionId,
        )

    companion object {
        /** qwen3-embedding 임베딩 차원. */
        const val EMBEDDING_DIMENSIONS = 4096

        /**
         * 도메인 모델과 임베딩 벡터로부터 새 엔티티를 만든다.
         *
         * @param knowHow 도메인 모델.
         * @param id 실제 사용할 PK (TSID 자동 발급 결과를 포함).
         * @param embedding 색인할 임베딩 벡터.
         */
        fun of(knowHow: KnowHow, id: String, embedding: FloatArray): KnowHowEntity =
            KnowHowEntity(
                id = id,
                chatId = knowHow.chatId,
                intent = knowHow.intent,
                body = knowHow.body,
                embedding = embedding,
                importance = knowHow.importance,
                useCount = knowHow.useCount,
                createdAt = knowHow.createdAt,
                updatedAt = knowHow.updatedAt,
                lastUsedAt = knowHow.lastUsedAt,
                sourceSessionId = knowHow.sourceSessionId,
            )
    }
}
