package org.tianea.secretary.core.agent.knowhow

import io.hypersistence.tsid.TSID
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln

/**
 * `know_how` 테이블에 대한 CRUD + 벡터 유사도 검색 저장소.
 *
 * 영속화는 Spring Data JPA [KnowHowRepository]에 위임하고, 임베딩은 Spring AI
 * [EmbeddingModel] 빈을 직접 호출해 생성한다. 도메인 모델 [KnowHow]와 JPA 엔티티
 * [KnowHowEntity] 사이의 변환도 이 클래스가 담당한다.
 *
 * 4096차원 벡터는 pgvector HNSW/IVFFlat 한계(≤2000)를 초과하므로 인덱스를 만들지 않고
 * 풀스캔으로 운용한다. 노하우 행 수는 raw 메시지보다 훨씬 적으므로 이 방식이 충분하다.
 *
 * @param repository `know_how` 테이블 JPA 저장소.
 * @param embeddingModel Spring AI 임베딩 모델 빈.
 * @param entityManager 스키마 초기화용 네이티브 DDL 실행에 사용.
 */
@Repository
class KnowHowStore(
    private val repository: KnowHowRepository,
    private val embeddingModel: EmbeddingModel,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(KnowHowStore::class.java)

    /**
     * `know_how` 테이블과 필요한 extension을 초기화한다.
     *
     * Hibernate `ddl-auto`가 아니라 명시적 DDL을 쓰는 이유: `embedding` 컬럼의 `vector(4096)`
     * 타입은 `vector` extension이 먼저 설치돼 있어야 생성할 수 있는데, Hibernate 스키마 생성은
     * extension 설치와의 순서를 보장하지 못한다. 같은 트랜잭션 안에서 extension → 테이블 순으로
     * 직접 실행해 순서를 확정한다. `CREATE ... IF NOT EXISTS`이므로 재기동 시 멱등하다.
     *
     * `@PostConstruct`가 아니라 [ApplicationReadyEvent] 리스너인 이유: 네이티브 DDL `executeUpdate`는
     * 활성 트랜잭션을 요구하는데, `@PostConstruct` 시점에는 트랜잭션 프록시가 아직 적용되지 않는다.
     *
     * `CREATE EXTENSION`은 SUPERUSER 권한을 요구할 수 있으므로 실패해도 부팅을 막지 않고 경고만
     * 남긴다 — 운영 환경에서는 DBA가 extension을 사전 설치하는 게 일반적이며, 그 경우 본 호출은
     * no-op이 되어 정상 진행된다.
     *
     * 신규 컬럼([KnowHowEntity.updatedAt])은 `ADD COLUMN IF NOT EXISTS`로 기존 테이블에도 멱등하게
     * 추가되므로 `spring.jpa.hibernate.ddl-auto=none` 상태로 안전하게 운용할 수 있다.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun initSchema() {
        runCatching {
            entityManager.createNativeQuery("CREATE EXTENSION IF NOT EXISTS vector").executeUpdate()
        }.onFailure { error ->
            log.warn(
                "vector extension 자동 설치 실패 — DBA가 사전 설치한 상태인지 확인 필요: {}",
                error.message,
            )
        }
        entityManager
            .createNativeQuery(
                """
                CREATE TABLE IF NOT EXISTS know_how (
                    id                text PRIMARY KEY,
                    chat_id           bigint         NOT NULL,
                    intent            text           NOT NULL,
                    body              text           NOT NULL,
                    embedding         vector(${KnowHowEntity.EMBEDDING_DIMENSIONS})   NOT NULL,
                    importance        double precision NOT NULL DEFAULT 0.5,
                    use_count         int            NOT NULL DEFAULT 0,
                    created_at        timestamptz    NOT NULL,
                    updated_at        timestamptz    NOT NULL,
                    last_used_at      timestamptz,
                    source_session_id text           NOT NULL
                )
                """.trimIndent(),
            ).executeUpdate()
        entityManager
            .createNativeQuery("ALTER TABLE know_how ADD COLUMN IF NOT EXISTS updated_at timestamptz")
            .executeUpdate()
        entityManager
            .createNativeQuery("UPDATE know_how SET updated_at = created_at WHERE updated_at IS NULL")
            .executeUpdate()
        entityManager
            .createNativeQuery("ALTER TABLE know_how ALTER COLUMN updated_at SET NOT NULL")
            .executeUpdate()
    }

    /**
     * 새 노하우를 저장한다.
     *
     * [KnowHow.id]가 비어 있으면 TSID로 자동 발급된다.
     * 임베딩은 [embeddingText]를 기준으로 생성된다 (기본값: [KnowHow.intent]).
     *
     * @param knowHow 저장할 도메인 객체.
     * @param embeddingText 임베딩 생성에 사용할 텍스트. 미지정 시 [KnowHow.intent] 사용.
     * @return 실제 저장된 id (TSID 자동 발급 시 새 값).
     */
    fun save(
        knowHow: KnowHow,
        embeddingText: String = knowHow.intent,
    ): String {
        val id = knowHow.id.ifBlank { TSID.fast().toString() }
        val embedding = embeddingModel.embed(embeddingText)
        repository.save(KnowHowEntity.of(knowHow, id, embedding))
        return id
    }

    /**
     * 기존 노하우의 intent·body·importance를 갱신한다. 임베딩도 [embeddingText] 기준으로 재생성된다.
     * 해당 id가 없으면 no-op.
     *
     * @param id 갱신할 노하우 id.
     * @param intent 새 intent 텍스트.
     * @param body 새 body 텍스트.
     * @param importance 새 importance 값.
     * @param embeddingText 임베딩 생성 기준 텍스트. 미지정 시 [intent] 사용.
     */
    fun update(
        id: String,
        intent: String,
        body: String,
        importance: Double,
        embeddingText: String = intent,
    ) {
        val existing = repository.findById(id).orElse(null) ?: return
        existing.intent = intent
        existing.body = body
        existing.importance = importance
        existing.embedding = embeddingModel.embed(embeddingText)
        existing.updatedAt = Instant.now()
        repository.save(existing)
    }

    /**
     * 노하우를 삭제한다.
     *
     * @param id 삭제할 노하우 id.
     */
    fun delete(id: String) {
        repository.deleteById(id)
    }

    /**
     * id로 단건 조회한다.
     *
     * @return 존재하지 않으면 null.
     */
    fun findById(id: String): KnowHow? = repository.findById(id).orElse(null)?.toDomain()

    /**
     * [chatId] 사용자의 노하우를 쿼리 텍스트와의 코사인 유사도 기준으로 검색한다.
     *
     * 코사인 거리가 작을수록 유사하므로 오름차순 정렬 후 상위 [topK]개를 반환한다.
     * 반환된 [ScoredKnowHow.rerankScore]는 초기값으로 similarity 와 동일하다.
     * [rerank] 를 통해 재랭킹하면 갱신된다.
     *
     * @param chatId 사용자 격리 키.
     * @param queryText 임베딩 생성에 사용할 쿼리 문자열.
     * @param topK 반환할 최대 결과 수.
     */
    fun search(
        chatId: Long,
        queryText: String,
        topK: Int,
    ): List<ScoredKnowHow> {
        val queryEmbedding = embeddingModel.embed(queryText)
        return repository
            .searchByEmbedding(chatId, queryEmbedding, PageRequest.of(0, topK))
            .map { row ->
                val entity = row[0] as KnowHowEntity
                val distance = (row[1] as Number).toDouble()
                ScoredKnowHow(
                    knowHow = entity.toDomain(),
                    similarity = (1.0 - distance).coerceIn(0.0, 1.0),
                )
            }
    }

    /**
     * consolidate 전용: intent 텍스트로 유사 노하우를 검색한다.
     *
     * [search]와 동일하나 chatId + intentText 기반으로 중복 후보를 찾는 데 특화.
     *
     * @param chatId 사용자 격리 키.
     * @param intent 비교 기준 intent 텍스트.
     * @param topK 반환할 최대 결과 수.
     */
    fun searchSimilar(
        chatId: Long,
        intent: String,
        topK: Int,
    ): List<ScoredKnowHow> = search(chatId, intent, topK)

    /**
     * 노하우가 retrieval에 채택될 때 호출한다.
     *
     * `use_count`를 1 증가시키고 `last_used_at`을 현재 시각으로 갱신한다.
     *
     * @param id 사용된 노하우 id.
     */
    fun touch(id: String) {
        repository.incrementUsage(id, Instant.now())
    }

    /**
     * Generative Agents 방식으로 재랭킹한다.
     *
     * `score = recency × importance × similarity`
     *
     * - recency: [lastUsedAt] 기준 지수감쇠. `exp(-λ × hours_since_last_used)`.
     *   [lastUsedAt]이 null이면 [createdAt] 기준. λ = `ln(2) / halfLifeHours`.
     * - importance: [KnowHow.importance] (0.0~1.0).
     * - similarity: [ScoredKnowHow.similarity] (0.0~1.0).
     *
     * @param candidates 재랭킹할 후보 목록.
     * @param halfLifeHours recency 반감기(시간). 기본 72시간(3일).
     * @return [ScoredKnowHow.rerankScore] 내림차순으로 정렬된 새 리스트.
     */
    fun rerank(
        candidates: List<ScoredKnowHow>,
        halfLifeHours: Double = 72.0,
    ): List<ScoredKnowHow> {
        val now = Instant.now()
        val lambda = ln(2.0) / halfLifeHours
        return candidates
            .map { scored ->
                val kh = scored.knowHow
                val referenceTime = kh.lastUsedAt ?: kh.createdAt
                val hoursSince = (now.epochSecond - referenceTime.epochSecond) / 3600.0
                val recency = exp(-lambda * hoursSince)
                val score = recency * kh.importance * scored.similarity
                scored.copy(rerankScore = score)
            }.sortedByDescending { it.rerankScore }
    }

    /**
     * 재랭킹된 후보 목록을 토큰 예산 이내로 잘라 반환한다.
     *
     * 토큰 수는 `body` 문자 수 / 4 로 근사한다 (UTF-8 영어 기준).
     * 이미 [rerank] 로 내림차순 정렬된 리스트를 전제한다.
     *
     * 각 후보를 admit하기 전에 누적 토큰이 예산을 초과하지 않는지 검사하므로, 첫 후보라도
     * 본문이 예산보다 크면 제외된다.
     *
     * @param candidates 재랭킹 완료된 후보 목록 (내림차순).
     * @param tokenBudget 허용 최대 근사 토큰 수.
     * @return 예산 이내 상위 후보.
     */
    fun applyTokenBudget(
        candidates: List<ScoredKnowHow>,
        tokenBudget: Int,
    ): List<ScoredKnowHow> {
        val result = mutableListOf<ScoredKnowHow>()
        var used = 0
        for (scored in candidates) {
            val tokens = scored.knowHow.body.length / 4
            if (used + tokens > tokenBudget) break
            result += scored
            used += tokens
        }
        return result
    }
}
