# 장기기억 — VectorStore 기반 의미검색 메모리

## 목적

세션 윈도우(`window-size: 20`)를 넘어가는 과거 대화에서도 **의미적으로 관련된 부분을 자동 회수**해 prompt에 주입한다. 단기기억(`SPRING_AI_CHAT_MEMORY`)이 "최근 N개 메시지"의 시간순 컨텍스트라면, 장기기억은 "주제·의미가 비슷한 임의 시점의 메시지"를 다룬다.

> 본 문서가 다루는 `vector_store`는 **원문 메시지** 벡터다. 같은 4096차원 임베딩을 공유하지만 **추출된 절차적 노하우**는 `know_how` 별도 테이블에 저장된다 — 메타데이터·수명주기가 다르기 때문. 자세한 내용은 [know-how-memory.md](./know-how-memory.md).

## 설계 — Koog `LongTermMemory` + Spring AI `PgVector` 결합

본 프로젝트는 [docs/chat-memory.md](./chat-memory.md)와 동일한 하이브리드 패턴을 한 단계 더 적용한다 — **Koog feature가 정책을 담당하고 Spring AI가 인프라를 담당**한다.

```
[AIAgent.run(input, sessionId)]
        │
        ▼
[install(LongTermMemory)] ── 매 LLM 호출 ─→ retrieval/ingestion 자동 실행
                                                │
                                          (Koog 0.8.0 어댑터) ▼
                                              [SpringAiKoogVectorStore]      ← KoogVectorStore 인터페이스
                                                       │
                                                       ▼
                                           [Spring AI VectorStore]
                                                       │
                                          (자동 임베딩) ▼
                                            [Ollama qwen3-embedding:8b → host:11434]
                                                       │
                                                       ▼
                                         [PgVectorStore → Postgres + vector ext]
```

**의존성 두 쌍**이 핵심이다:
- `koog-spring-ai-starter-vector-store` → Spring AI `VectorStore` 빈을 받아 Koog `KoogVectorStore` 구현(`SpringAiKoogVectorStore`)으로 노출
- `koog-spring-ai-starter-model-embedding` → Spring AI `EmbeddingModel` 빈을 받아 Koog `LLMEmbeddingProvider`로 노출 (현재는 PgVectorStore가 EmbeddingModel을 직접 쓰지만, Koog 측 다른 경로에서도 사용 가능하게 어댑팅)

**Chat과 Embedding 모두 Ollama**:
- Chat: Ollama (`qwen3:4b-instruct-2507-q4_K_M`)
- Embedding: Ollama (`qwen3-embedding:8b`, host의 로컬 인스턴스)

Spring AI 자동구성은 classpath의 provider를 `matchIfMissing=true`로 켜므로, 백엔드를 고정하려면 `spring.ai.model.chat=ollama`, `spring.ai.model.embedding=ollama`를 명시한다.

## 구현 요소

### 의존성

```toml
# gradle/libs.versions.toml
[libraries]
koog-spring-ai-starter-vector-store        = { module = "ai.koog:koog-spring-ai-starter-vector-store",        version.ref = "koog" }
koog-spring-ai-starter-model-embedding     = { module = "ai.koog:koog-spring-ai-starter-model-embedding",     version.ref = "koog" }
spring-ai-starter-vector-store-pgvector    = { module = "org.springframework.ai:spring-ai-starter-vector-store-pgvector" }
spring-ai-starter-model-ollama             = { module = "org.springframework.ai:spring-ai-starter-model-ollama" }
```

### 데이터베이스 — Postgres 18 + pgvector

`docker-compose.yml`은 베이스 이미지를 `pgvector/pgvector:pg18-trixie`로 변경. PGDATA 경로가 동일해 기존 volume의 단기기억 데이터(`SPRING_AI_CHAT_MEMORY`)는 그대로 사용 가능. 다만 **컨테이너 재생성**이 필요하다:

```bash
docker compose down
docker compose up -d
# 첫 부팅 시 Spring AI starter가 자동 실행:
#   CREATE EXTENSION IF NOT EXISTS vector;
#   CREATE EXTENSION IF NOT EXISTS hstore;
#   CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
#   CREATE TABLE IF NOT EXISTS vector_store (
#       id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
#       content text, metadata json, embedding vector(4096)
#   );
#   -- 인덱스는 만들지 않음. PgVector HNSW/IVFFlat은 ≤2000 차원만 지원하므로
#   -- 4096 차원에서는 인덱스 없이 풀스캔으로 운용한다 (아래 "알려진 제약 6" 참조)
```

### `application.yaml`

```yaml
spring:
  ai:
    model:
      chat: ollama                               # provider 자동구성 고정 (matchIfMissing=true)
      embedding: ollama
    ollama:
      base-url: http://localhost:11434           # host의 로컬 Ollama
      embedding:
        options:
          model: qwen3-embedding:8b              # 4096차원, MRL 지원
      init:
        pull-model-strategy: when_missing        # 모델 미존재 시 첫 부팅 때 자동 pull
        timeout: 10m                             # 8b 모델은 큼, 기본 5m으론 부족
        max-retries: 1
    vectorstore:
      pgvector:
        initialize-schema: true                  # 1.x에서 기본 false — opt-in
        index-type: NONE                         # 4096차원은 HNSW(≤2000) 사용 불가 — 풀스캔
        distance-type: COSINE_DISTANCE
        dimensions: 4096                         # qwen3-embedding:8b 출력 차원
secretary:
  chat:
    long-term-memory:
      top-k: 5                                   # similarity search 상위 K
```

### Agent 설정

```kotlin
// src/main/kotlin/org/tianea/secretary/config/AgentConfig.kt
@Configuration
class AgentConfig {
    @OptIn(ExperimentalAgentsApi::class)
    @Bean
    fun assistantAgent(
        promptExecutor: PromptExecutor,
        historyProvider: ChatHistoryProvider,
        vectorStorage: KoogVectorStore,                                  // koog-spring-ai-starter-vector-store가 자동 등록
        @Value($$"${secretary.chat.memory.window-size}") windowSize: Int,
        @Value($$"${secretary.chat.long-term-memory.top-k}") topK: Int,
    ): AIAgent<String, String> = AIAgent(...) {
        install(ChatMemory) {
            chatHistoryProvider = historyProvider
            windowSize(windowSize)
        }
        install(LongTermMemory) {
            retrieval {
                storage        = vectorStorage
                searchStrategy = SimilaritySearchStrategy(topK = topK)
            }
        }
    }
}
```

`@OptIn(ExperimentalAgentsApi::class)` — Koog 0.8.0에서 `LongTermMemory`는 **Experimental** 표시. 0.7→0.8 사이 RAG 기반 추상화가 재정비되었으므로 minor 업그레이드 시 시그니처 변경 가능성 있음.

## 동작 흐름

1. 사용자가 `ask 작년 여름에 다녀온 여행 이름이 뭐였지?` 입력
2. Koog `LongTermMemory` feature가 입력을 임베딩 → `vector_store`에서 코사인 유사도 상위 `topK=5` 회수
3. 회수된 메시지를 시스템 프롬프트에 주입한 뒤 ChatMemory의 최근 윈도우와 함께 LLM 호출
4. 응답 생성 후 누적된 메시지를 자동으로 `vector_store`에 임베딩·저장 (ingestion)

기본 timing은 **`ON_LLM_CALL`** — 매 LLM 호출 직전 retrieval, 직후 ingestion. `ON_AGENT_COMPLETION`으로 바꿀 수도 있다.

## 알려진 제약

### 1. sessionId(TSID) 단위 분리는 검증 필요

설계 의도는 단기기억과 동일한 sessionId(TSID) 단위 격리지만, **`LongTermMemory` feature가 `agent.run(input, runId)`의 두 번째 인자를 자동으로 namespace로 사용하는지는 공식 문서에 명시되어 있지 않다**. 현재 구현은 명시적 namespace 없이 install했으므로:

- 자동으로 runId 분리되면 → 의도대로 동작
- 자동 분리가 없으면 → 단일 컬렉션에 누적 (모든 세션이 서로의 장기기억을 공유)

런타임 동작 확인 필요. 분리가 안 되면 retrieval block에 명시적 namespace 또는 메타데이터 필터를 추가해야 함. [Koog 공식 문서 — long-term-memory](https://docs.koog.ai/features/long-term-memory/)에서 namespace 파라미터를 사용하는 패턴을 참고.

### 2. Prompt flooding 가능성

[Koog issue #1001](https://github.com/JetBrains/koog/issues/1001) — `AgentMemory`(LongTermMemory와는 별개의 feature) replay에서 토큰 budget 부재로 prompt가 비대해진 사례 보고. `LongTermMemory`도 유사 이슈 가능성이 있어 `topK=5`로 보수적 시작. 실제 운영에서 prompt 길이를 모니터링하면서 조정.

### 3. Provider별 자동활성화 충돌 (`spring.ai.model.*` 명시 필수)

Spring AI 1.x의 chat/embedding 자동구성은 모두 `@ConditionalOnProperty(name="spring.ai.model.{chat|embedding}", havingValue="<provider>", matchIfMissing=true)`. **명시 안 하면 classpath의 모든 provider가 자동 활성화**되어 같은 종류의 빈이 중복 등록될 수 있고, Koog의 `PromptExecutor`가 어느 `ChatModel`을 쓸지 모호해져 컨텍스트 로드가 실패한다. 현재는 Ollama starter 하나만 의존하지만, 백엔드를 명시적으로 고정하기 위해 다음을 둔다:

```yaml
spring.ai.model.chat: ollama
spring.ai.model.embedding: ollama
```

### 4. Koog 0.8.0 Beta API 의존

`@ExperimentalAgentsApi` 표기. minor 업그레이드 시 DSL/시그니처 변경 가능. 핵심 비즈니스 로직을 `LongTermMemory` strategy에 깊이 의존시키기 전에 1.0 GA 타이밍을 확인할 것 ([Koog CHANGELOG](https://github.com/JetBrains/koog/blob/main/CHANGELOG.md)).

### 5. 차원 불일치는 컴파일이 아닌 런타임 에러

`spring.ai.vectorstore.pgvector.dimensions`(현재 4096)와 임베딩 모델 출력 차원이 어긋나면 첫 ingestion에서 `ERROR: expected 4096 dimensions, not N`로 실패. 모델 변경 시 두 값을 함께 업데이트 + `vector_store` 테이블 재생성(`remove-existing-vector-store-table: true` 또는 수동 DROP) 필요.

### 6. PgVector 인덱스 차원 한계 — 4096은 풀스캔만 가능

PgVector의 HNSW/IVFFlat 인덱스는 모두 **≤2000 차원**만 지원. qwen3-embedding:8b는 4096차원이라 인덱스를 만들 수 없어 `index-type: NONE` 으로 운용한다. 이 의미:

- `vector_store`가 작을 때(수천~수만 행)는 풀스캔도 충분히 빠름
- 데이터가 커지면 검색 시간이 행 수에 비례해 늘어남

데이터셋이 커지면 다음 중 하나를 선택:
- **MRL로 차원 축소** (qwen3-embedding은 32~4096 사이 사용자 정의 차원 지원). 단, Ollama API에서 출력 차원을 줄이는 공식 트리거는 [docs.spring.io ollama-embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html)에서 확인되지 않음 — 검증 필요
- **다른 모델로 교체** (예: `qwen3-embedding:0.6b` → 1024차원, HNSW 가능)
- **다른 vector backend로 교체** (Qdrant/Milvus 등은 더 높은 차원에서 인덱스 제공)

### 7. Ollama 모델 첫 pull은 시간 소요

`pull-model-strategy: when_missing` + `timeout: 10m` 설정. 8b 모델은 수 GB 단위라 첫 부팅에서 다운로드 시간이 오래 걸린다. 사전 `ollama pull qwen3-embedding:8b` 권장.

### 8. Koog starter가 `spring-webflux`를 transitive로 끌어옴 → reactive web app으로 오인

`koog-spring-ai-starter-*` 시리즈와 `koog-spring-ai-common`이 `spring-boot-dependencies` BOM constraint를 통해 `spring-webflux`를 runtime classpath에 포함시킨다. Spring Boot가 이를 발견하면 자동으로 reactive web app으로 인식해 `ReactiveWebServerFactory` 빈을 찾으려다 실패:

```
Web application could not be started as there was no
org.springframework.boot.web.reactive.server.ReactiveWebServerFactory bean defined
```

본 프로젝트는 Shell 앱이라 web server가 불필요. `application.yaml`에 다음을 명시해 자동 인식을 차단:

```yaml
spring.main.web-application-type: none
```

테스트(`./gradlew test`)는 `@SpringBootTest`가 web environment를 mock으로 처리해 통과하지만, 실제 `bootRun`/IDE 실행 시점에 드러난다.

## 출처

- [Koog Long-term memory](https://docs.koog.ai/features/long-term-memory/)
- [Koog Spring AI integration](https://docs.koog.ai/spring-ai-integration/)
- [JetBrains 블로그 — Koog × Spring AI (2026-04)](https://blog.jetbrains.com/ai/2026/04/introducing-koog-integration-for-spring-ai-smarter-orchestration-for-your-agents/)
- [Spring AI PGvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)
- [Spring AI Ollama Embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html)
- [Qwen3-Embedding-8B (Hugging Face)](https://huggingface.co/Qwen/Qwen3-Embedding-8B) / [ollama.com/library/qwen3-embedding](https://ollama.com/library/qwen3-embedding)
- [pgvector GitHub](https://github.com/pgvector/pgvector) / [Docker Hub pgvector/pgvector](https://hub.docker.com/r/pgvector/pgvector)
- 관련 코드: `src/main/kotlin/org/tianea/secretary/config/AgentConfig.kt`, `src/main/resources/application.yaml`, `docker-compose.yml`, `gradle/libs.versions.toml`, `build.gradle.kts`
- 관련 문서: [docs/chat-memory.md](./chat-memory.md), [docs/koog-vs-spring-ai.md](./koog-vs-spring-ai.md)
